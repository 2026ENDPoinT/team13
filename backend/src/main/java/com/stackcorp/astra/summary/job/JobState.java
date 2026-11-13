package com.stackcorp.astra.summary.job;

public enum JobState {

	QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

	String value() {
		return name().toLowerCase();
	}

	static JobState of(String value) {
		return valueOf(value.toUpperCase());
	}

	public boolean terminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}

}
