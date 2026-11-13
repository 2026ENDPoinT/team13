package com.stackcorp.astra.summary.chat;

public enum ExchangeState {

	QUEUED, RUNNING, ANSWERED, FAILED, CANCELLED;

	String value() {
		return name().toLowerCase();
	}

	static ExchangeState of(String value) {
		return valueOf(value.toUpperCase());
	}

}
