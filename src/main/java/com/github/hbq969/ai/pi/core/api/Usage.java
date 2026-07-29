package com.github.hbq969.ai.pi.core.api;

/**
 * Token 用量。
 */
public class Usage {

    private long input;
    private long output;
    private long cacheRead;
    private long cacheWrite;

    public Usage() {
    }

    public Usage(long input, long output, long cacheRead, long cacheWrite) {
        this.input = input;
        this.output = output;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
    }

    public long input() {
        return input;
    }

    public long output() {
        return output;
    }

    public long cacheRead() {
        return cacheRead;
    }

    public long cacheWrite() {
        return cacheWrite;
    }

    public long total() {
        return input + output + cacheRead + cacheWrite;
    }

    public void addInput(long n) {
        this.input += n;
    }

    public void addOutput(long n) {
        this.output += n;
    }

    public void addCacheRead(long n) {
        this.cacheRead += n;
    }

    public void addCacheWrite(long n) {
        this.cacheWrite += n;
    }

    @Override
    public String toString() {
        return "Usage{input=" + input + ", output=" + output + ", total=" + total() + "}";
    }
}
