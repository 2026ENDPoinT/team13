package com.stackcorp.astra.summary.pipeline;

@FunctionalInterface
public interface Cancellation {

	Cancellation NEVER = () -> false;

	boolean isCancelled();

}
