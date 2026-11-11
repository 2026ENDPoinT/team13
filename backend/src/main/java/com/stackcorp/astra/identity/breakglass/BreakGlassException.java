package com.stackcorp.astra.identity.breakglass;

class BreakGlassException extends RuntimeException {

	enum Reason {
		DISABLED, REJECTED, LOCKED
	}

	private final Reason reason;

	BreakGlassException(Reason reason) {
		super(reason.name());
		this.reason = reason;
	}

	Reason reason() {
		return this.reason;
	}

}
