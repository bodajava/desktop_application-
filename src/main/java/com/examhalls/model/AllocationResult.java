package com.examhalls.model;

public record AllocationResult(long examId, int assigned, int unfilled) {

    public boolean complete() {
        return unfilled == 0;
    }
}
