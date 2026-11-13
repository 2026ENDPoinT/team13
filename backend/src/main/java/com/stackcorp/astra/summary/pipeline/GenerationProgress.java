package com.stackcorp.astra.summary.pipeline;

public record GenerationProgress(Stage stage, int pagesDone, int pagesTotal, Usage usage) {

	public enum Stage {
		PAGES, STORY, VALIDATE, DONE
	}

}
