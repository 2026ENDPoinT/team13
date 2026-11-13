package com.stackcorp.astra.summary.pipeline;

import java.util.List;

import com.stackcorp.astra.summary.pack.Beat;

record StoryResult(String question, List<Beat> story) {
}
