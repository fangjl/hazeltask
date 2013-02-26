package com.hazeltask.executor.task;

import java.io.Serializable;
import java.util.concurrent.Callable;

/**
 * This class wraps a runnable and provides other metadata we need to searching work items
 * in the distributed map.
 * 
 * @author jclawson
 *
 */
public class HazeltaskTask<ID extends Serializable, G extends Serializable> 
    implements Runnable, Task<ID,G> {
	private static final long serialVersionUID = 1L;
	
	private Runnable runTask;
	private Callable<?> callTask;
	
	private long createdAtMillis;
	private ID id;
	private G group;
	private String topology;
	private int submissionCount;
	
	private volatile transient Object result;
    private volatile transient Exception e;
	
	public HazeltaskTask(String topology, ID id, G group, Runnable task){
		this.runTask = task;
		this.id = id;
		this.group = group;
		this.topology = topology;
		createdAtMillis = System.currentTimeMillis();
		this.submissionCount = 1;
	}
	
	public HazeltaskTask(String topology, ID id, G group, Callable<?> task){
        this.callTask = task;
        this.id = id;
        this.group = group;
        this.topology = topology;
        createdAtMillis = System.currentTimeMillis();
        this.submissionCount = 1;
    }
	
	public void setSubmissionCount(int submissionCount){
	    this.submissionCount = submissionCount;
	}
	
	public int getSubmissionCount(){
	    return this.submissionCount;
	}
	
	public void updateCreatedTime(){
	    this.createdAtMillis = System.currentTimeMillis();
	}
	
//	public Runnable getDelegate(){
//	    return task;
//	}
	
	public String getTopologyName() {
		return topology;
	}

	public G getGroup() {
		return group;
	}

	public Object getResult() {
        return result;
    }

    public Exception getException() {
        return e;
    }
	
	public long getTimeCreated(){
		return createdAtMillis;
	}

    public void run() {
        try {
            if(callTask != null) {
    		    this.result = callTask.call();
    		} else {
    		    runTask.run();
    		}
        } catch (Exception t) {
            this.e = t;
        }
	}
    
    public Runnable getInnerRunnable() {
        return this.runTask;
    }
    
    public Callable<?> getInnerCallable() {
        return this.callTask;
    }

    @Override
    public ID getId() {
        return id;
    }
	
}