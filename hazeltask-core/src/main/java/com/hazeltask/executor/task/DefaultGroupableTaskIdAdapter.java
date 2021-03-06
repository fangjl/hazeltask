package com.hazeltask.executor.task;

import java.io.Serializable;

import com.hazeltask.core.concurrent.collections.grouped.Groupable;

public class DefaultGroupableTaskIdAdapter<G extends Serializable> implements TaskIdAdapter<Groupable<G>, G, Serializable> {

    @Override
    public G getTaskGroup(Groupable<G> task) {
        return task.getGroup();
    }

    @Override
    public boolean supports(Object task) {
       return task instanceof Groupable;
    }

    @Override
    public Serializable getTaskInfo(Groupable<G> task) {
        return null;
    }

}
