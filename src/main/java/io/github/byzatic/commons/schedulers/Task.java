package io.github.byzatic.commons.schedulers;

public interface Task extends Runnable{
    void observer(TaskStateControlObserverInterface stateControlObserver);
}
