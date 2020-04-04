package io.github.zacharynapier.spindle;

/** A unit of work run on each fire. Throwing any exception marks the attempt as failed. */
@FunctionalInterface
public interface Job {

    void run() throws Exception;
}
