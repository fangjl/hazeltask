package com.hazeltask.executor.local;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazeltask.HazeltaskTopology;
import com.hazeltask.config.ExecutorConfig;
import com.hazeltask.core.concurrent.DefaultThreadFactory;
import com.hazeltask.core.concurrent.collections.grouped.GroupedPriorityQueue;
import com.hazeltask.core.concurrent.collections.grouped.GroupedQueueRouter;
import com.hazeltask.core.concurrent.collections.tracked.ITrackedQueue;
import com.hazeltask.core.concurrent.collections.tracked.TrackedPriorityBlockingQueue.TimeCreatedAdapter;
import com.hazeltask.core.metrics.MetricNamer;
import com.hazeltask.executor.DelegatingExecutorListener;
import com.hazeltask.executor.ExecutorListener;
import com.hazeltask.executor.IExecutorTopologyService;
import com.hazeltask.executor.ResponseExecutorListener;
import com.hazeltask.executor.metrics.CollectionSizeGauge;
import com.hazeltask.executor.metrics.WorkThroughputGauge;
import com.hazeltask.executor.task.HazelcastWork;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

/**
 * 
 * @author Jason Clawson
 *
 * TODO: allow the specification of a regex for group name
 *       this regex will parse out interesting parameters 
 *       that we can query against to get queues... for example:
 *       
 *       groups:
 *         customer-123:com.example.Foo
 *         customer-123:com.example.Bar
 *         customer-456:com.example.Foo
 *       
 *       regex: customer-(\d+):(.*) -- customerId, className
 *       
 *       Then we can query like... getQueues("customerId", 123) : returns #1 and #2
 *       or 
 *       getQueues("className", "com.example.Foo") returns #1 and #3
 *       
 *       It might be nice to be able to use the hazelcast index class
 *       
 *       This will allow us to, for example, count the total items in a customer's queues
 *       or total up all queues of a certain priority number
 *
 */
public class LocalTaskExecutorService {

    private static ILogger LOGGER = Logger.getLogger(LocalTaskExecutorService.class.getName());
    
	private final HazeltaskTopology topology;
	private QueueExecutor localExecutorPool;
	private GroupedPriorityQueue<HazelcastWork> taskQueue;
	private final Collection<ExecutorListener> listeners = new LinkedList<ExecutorListener>();
	private final IExecutorTopologyService executorTopologyService;
	
	private final int maxThreads;
	
	private MetricNamer metricNamer;
	private Timer workSubmittedTimer;
	private Timer workExecutedTimer;
	
	public LocalTaskExecutorService(HazeltaskTopology topology, ExecutorConfig executorConfig, IExecutorTopologyService executorTopologyService) {
		this.topology = topology;
		this.maxThreads = executorConfig.getThreadCount();
		this.metricNamer = topology.getHazeltaskConfig().getMetricNamer();
		this.executorTopologyService = executorTopologyService;
		
		DefaultThreadFactory factory = new DefaultThreadFactory("DistributedTask",topology.getName());
		
		taskQueue = new GroupedPriorityQueue<HazelcastWork>(new GroupedQueueRouter.RoundRobinPartition<HazelcastWork>(),
                new TimeCreatedAdapter<HazelcastWork>(){
            public long getTimeCreated(HazelcastWork item) {
                return item.getTimeCreated();
            }
        });
		
		if(topology.getHazeltaskConfig().getMetricsRegistry() != null) {
			//TODO: move metrics to ExecutorMetrics class
		    MetricsRegistry metrics = topology.getHazeltaskConfig().getMetricsRegistry();
		    workSubmittedTimer = metrics.newTimer(createName("Work submitted"), TimeUnit.MILLISECONDS, TimeUnit.MINUTES);
			workExecutedTimer = metrics.newTimer(createName("Work executed"), TimeUnit.MILLISECONDS, TimeUnit.MINUTES);
			metrics.newGauge(createName("Throughput"), new WorkThroughputGauge(workSubmittedTimer, workExecutedTimer));
			metrics.newGauge(createName("Queue size"), new CollectionSizeGauge(taskQueue));
		}
		
		localExecutorPool = new QueueExecutor(taskQueue, maxThreads, factory, workExecutedTimer);
		localExecutorPool.addListener(new DelegatingExecutorListener(listeners));
		
		if(executorConfig.isFutureSupportEnabled())
		    addListener(new ResponseExecutorListener(executorTopologyService, topology.getLoggingService()));
		
		addListener(new WorkCompletionExecutorListener());
		
	}
	
	private MetricName createName(String name) {
		return metricNamer.createMetricName(
			"hazelcast-work", 
			topology.getName(), 
			"LocalWorkExecutor", 
			name
		);
	}

	public synchronized void startup(){
		 localExecutorPool.startup();
		 LOGGER.log(Level.FINE, "LocalTaskExecutorService started for "+topology.getName());
	}
	
	/**
     * This is not thread safe
     * @param listener
     */
    public void addListener(ExecutorListener listener) {
        listeners.add(listener);
    }
    
    private class WorkCompletionExecutorListener implements ExecutorListener {
        public void afterExecute(HazelcastWork runnable, Throwable exception) {
            HazelcastWork work = (HazelcastWork)runnable;
            //TODO: add task exceptions handling / retry logic
            //for now, just remove the work because its completed
            executorTopologyService.removePendingTask(work);
        }

        public boolean beforeExecute(HazelcastWork runnable) {return true;}
    }
	
	/**
	 * There is a race condition scenario here.  We want to get the best result possible as this value
	 * is used to determine what work needs to be recovered.
	 * 
	 * @return
	 */
	public Long getOldestWorkCreatedTime(){
	    long oldest = Long.MAX_VALUE;
	    
	    //there is a tiny race condition here... but we just want to make our best attempt
	    for(Runnable r : localExecutorPool.getTasksInProgress()) {
	        long timeCreated = ((HazelcastWork)r).getTimeCreated();
	        if(timeCreated < oldest) {
	            oldest = timeCreated;
	        }
	    }
	    
	    Long oldestQueueTime = this.taskQueue.getOldestQueueTime();
	    
	    if(oldestQueueTime != null && oldestQueueTime < oldest)
	        oldest = oldestQueueTime;
	    
	    return oldest;
	}
	
	public long getQueueSize() {
	    return this.taskQueue.size();
	}
	
	public Map<String, Integer> getGroupSizes() {
		Map<String, Integer> result = new HashMap<String, Integer>();
		for(String group : this.taskQueue.getGroups()) {
			result.put(group, this.taskQueue.getQueueByGroup(group).size());
		}
		return result;
	}
	
	public boolean execute(HazelcastWork command) {
		if(localExecutorPool.isShutdown()) {
		    LOGGER.log(Level.WARNING, "Cannot enqueue the task "+command+".  The executor threads are shutdown.");
		    return false;
		}
	    
	    TimerContext tCtx = null;
		if(workSubmittedTimer != null)
			tCtx = workSubmittedTimer.time();
		try {
			return taskQueue.add(command);
		} finally {
			if(tCtx != null)
				tCtx.stop();
		}
	}
	
	public Collection<HazelcastWork> stealTasks(long numberOfTasks) {
	    if(!this.localExecutorPool.isShutdown()) {
    	    long totalSize = taskQueue.size();
    	    ArrayList<HazelcastWork> result = new ArrayList<HazelcastWork>((int)numberOfTasks);
    	    for(ITrackedQueue<HazelcastWork> q : this.taskQueue.getQueuesByGroup().values()) {
    	        int qSize = q.size();
    	        if(qSize == 0) continue;
    	        
    	        double p = (double)qSize / (double)totalSize;
    	        long tasksToTake = Math.round(numberOfTasks * p);
    	        
    	        for(int i=0; i < tasksToTake; i++) {
    	            //TODO: this really sucks that we use q.poll() ... why can't this be a dequeue????
    	            HazelcastWork task = q.poll();
    	            if(task == null)
    	                break;
    	            result.add(task);
    	        }
    	    }
    	    
    	    if(result.size() < numberOfTasks) {
    	        //FIXME: should we really care? or is this good enough...
    	    }   
    	    
    	    return result;
	    } else {
	        LOGGER.log(Level.WARNING,"Cannot steal "+numberOfTasks+" tasks.  The executor threads are shutdown.");
	        return Collections.emptyList();
	    }
	}

	//TODO: time how long it takes to shutdown
	public void shutdown() {
	    localExecutorPool.shutdown();
	}
	
	//TODO: time how long it takes to shutdown
	public List<HazelcastWork> shutdownNow() {
	    return localExecutorPool.shutdownNow();
	}

	public boolean isShutdown() {
		return localExecutorPool.isShutdown();
	}

//	//FIXME: fix this
//	public boolean isTerminated() {
//		return localExecutorPool.isShutdown();
//	}

}