package com.succinctllc.hazelcast.work.executor;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import com.hazelcast.core.DistributedTask;
import com.hazelcast.core.Member;
import com.hazelcast.core.MultiTask;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.succinctllc.core.concurrent.collections.grouped.Groupable;
import com.succinctllc.hazelcast.work.HazelcastWork;
import com.succinctllc.hazelcast.work.HazelcastWorkManager;
import com.succinctllc.hazelcast.work.HazelcastWorkTopology;
import com.succinctllc.hazelcast.work.WorkIdAdapter;
import com.succinctllc.hazelcast.work.WorkId;
import com.succinctllc.hazelcast.work.executor.DistributedExecutorServiceBuilder.InternalBuilderStep2;
import com.succinctllc.hazelcast.work.router.ListRouter;
import com.succinctllc.hazelcast.work.router.RoundRobinRouter;

/**
 * This is basically a proxy for executor service that returns nicely generic futures
 * it wraps the work in another callable.  It puts it into the HC map for writeAheadLog
 * it sends a message of the work to other nodes
 * 
 * TODO: a lot... most methods throw a not implemented exception
 * 
 * TODO: should this be an ExecutorService that can handle Runnable / Callables?  Or should
 * we define another type of object like Work?  This would help our generics a lot and ensuring
 * its serializable. ... I actually like this better since it more expressive.  I don't think we 
 * lose much giving up compatibility with ExecutorService
 * 
 * @author jclawson
 *
 */
public class DistributedExecutorService implements ExecutorService {
    private static ConcurrentMap<String, DistributedExecutorService> servicesByTopology = new ConcurrentHashMap<String, DistributedExecutorService>();

    public static DistributedExecutorService get(String topology) {
        return (DistributedExecutorService) servicesByTopology.get(topology);
    }
    
    private static ILogger LOGGER = Logger.getLogger(DistributedExecutorService.class.getName());
    //private DistributedExecutorServiceManager distributedExecutorServiceManager;
    
    private volatile boolean         isReady  = false;
    private final ListRouter<Member> memberRouter;
    private final WorkIdAdapter     partitionAdapter;
    private final LocalWorkExecutorService                                  localExecutorService;
    private final HazelcastWorkTopology topology;
    private final ExecutorService                                           workDistributor;
    
    
    
	public static interface RunnablePartitionable extends Runnable, Groupable {
		
	}
	
	protected DistributedExecutorService(InternalBuilderStep2<?> internalBuilderStep1){
		this.topology = internalBuilderStep1.topology;
		this.partitionAdapter = internalBuilderStep1.partitionAdapter;
		workDistributor = topology.getWorkDistributor();
		
		if (servicesByTopology.putIfAbsent(topology.getName(), this) != null) { 
		    throw new IllegalArgumentException(
                "A DistributedExecutorService already exists for the topology "
                        + topology.getName()); 
		}
		
		
		this.localExecutorService = new LocalWorkExecutorService(topology);		
		memberRouter = new RoundRobinRouter<Member>(new Callable<List<Member>>() {
            public List<Member> call() throws Exception {
                return topology.getReadyMembers();
            }
        });
	}
	
	public void startup() {
	    localExecutorService.start();
        isReady = true;
	    //flush items that got left behind in the map
	    new Timer(topology.createName("flush-timer"), true)
            .schedule(new StaleItemsFlushTimerTask(this), 6000, 6000);
	}
	
	public HazelcastWorkTopology getTopology(){
	    return this.topology;
	}
	
	
	
//	@Override
//	protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
//        return new DistributedRunnableFuture<T>(callable);
//    }
//	
//	@Override
//	protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
//        return new DistributedRunnableFuture<T>(runnable, value);
//    }

	public LocalWorkExecutorService getLocalExecutorService() {
        return localExecutorService;
    }
	
	

    public boolean isReady() {
        return isReady;
    }

    public void execute(Runnable command) {
	    execute(command, false);
	}
	
	//TODO: make HazelcastWork package protected, detect if we are resubmitting if command instanceof HazelcastWork
	protected void execute(Runnable command, boolean isResubmitting) {
		//TODO: make sure this command is hazelcast serializable
		//IMap<String, HazelcastWork> map = this.map;
		HazelcastWork wrapper;
		WorkId workKey;
		
		//if resubmitting a HazelcastWork, we make sure not to double wrap
		if(command instanceof HazelcastWork) {
		    wrapper = (HazelcastWork) command;
		    wrapper.updateCreatedTime();
		    workKey = ((HazelcastWork) command).getWorkId();
		} else {
		    workKey = partitionAdapter.getWorkId(command);
		    wrapper = new HazelcastWork(topology.getName(), workKey, command);
		}
		
		boolean executeTask = true;
		
		if(isResubmitting) {
		    wrapper.setSubmissionCount(wrapper.getSubmissionCount()+1);
		    topology.getPendingWork().put(workKey.getId(), wrapper);
		} else {
		    executeTask = topology.getPendingWork().putIfAbsent(workKey.getId(), wrapper) == null;
		}
		
		
		if(executeTask) {
		    Member m = memberRouter.next();
	        if(m == null) {
	            LOGGER.log(Level.WARNING, "Work submitted to writeAheadLog but no members are online to do the work.");
	            return;
	        }
	        
	        //NOTE: its possible to get into a loop of resubmitting things to be worked on, updating their
	        //created dates, if no members to do work are online.  We do keep track of the submission count
	        //so we could just eventually expire the work altogether
	        
	        DistributedTask<Object> task = new DistributedTask<Object>(new SubmitWorkTask(wrapper, topology.getName()), m);
	        workDistributor.execute(task);
		}
	}

	public <T> Future<T> submit(Callable<T> task) {
	    //TODO: make sure this task is hazelcast serializable
	    WorkId workKey = partitionAdapter.getWorkId(task);
	    //Future<T> future = new DistributedFuture<T>(topology.getName(), workKey);
	    throw new RuntimeException("Not Implemented Yet");
	}

	public void shutdown() {
		MultiTask<List<Runnable>> task = new MultiTask<List<Runnable>>(
				new ShutdownEvent(topology.getName(), 
						ShutdownEvent.ShutdownType.WAIT_AND_SHUTDOWN
					), 
					topology.getHazelcast().getCluster().getMembers());
		
		topology.getHazelcast().getExecutorService().execute(task);
	}

	public List<Runnable> shutdownNow() {
		MultiTask<List<Runnable>> task = new MultiTask<List<Runnable>>(
				new ShutdownEvent(topology.getName(), 
						ShutdownEvent.ShutdownType.SHUTDOWN_NOW
					), 
					topology.getHazelcast().getCluster().getMembers());
		
		topology.getHazelcast().getExecutorService().execute(task);
		try {
			LinkedList<Runnable> allRunnables = new LinkedList<Runnable>();			
			for(List<Runnable> list : task.get()) {
				allRunnables.addAll(list);
			}
			return allRunnables;
		} catch (ExecutionException e) {
			throw new RuntimeException("Execution exception while shutting down the executor services", e);
		} catch (InterruptedException e) {
			throw new RuntimeException("Thread interrupted while shutting down the executor services", e);
		}
	}


	public Future<?> submit(Runnable task) {
		//TODO: make sure this task is hazelcast serializable
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}

	public <T> Future<T> submit(Runnable task, T result) {
		//TODO: make sure this task is hazelcast serializable
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}
	
	
	public boolean isShutdown() {
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}

	public boolean isTerminated() {
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}

	public boolean awaitTermination(long timeout, TimeUnit unit)
			throws InterruptedException {
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}
	
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
			throws InterruptedException {
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}

	public <T> List<Future<T>> invokeAll(
			Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws InterruptedException {
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}

	public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
			throws InterruptedException, ExecutionException {
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}

	public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
			long timeout, TimeUnit unit) throws InterruptedException,
			ExecutionException, TimeoutException {
		// FIXME Implement this method
		throw new RuntimeException("Not Implemented Yet");
	}
	
	private static class ShutdownEvent implements Callable<List<Runnable>>, Serializable {
		private static final long serialVersionUID = 1L;


		private static enum ShutdownType {
			WAIT_AND_SHUTDOWN,
			SHUTDOWN_NOW
		}
		
		private final ShutdownType type;
		private String topology;
		
		private ShutdownEvent(String topology, ShutdownType type){
			this.type = type;
			this.topology = topology;
		}
		
		public List<Runnable> call() throws Exception {
			LocalWorkExecutorService svc = HazelcastWorkManager
			                                  .getDistributedExecutorService(topology)
			                                  .getLocalExecutorService();
			
			switch(this.type) {
			case SHUTDOWN_NOW:
				return svc.shutdownNow();
			case WAIT_AND_SHUTDOWN:
			default:
				svc.shutdown();
			}
			return null;
		}
	}



}
