package com.miningtrackeraddon.sync;

record SyncSendResult(Outcome outcome, int statusCode, String detail, String responseBody)
{
    static SyncSendResult success(int statusCode, String responseBody)
    {
        return new SyncSendResult(Outcome.SUCCESS, statusCode, "", responseBody == null ? "" : responseBody);
    }

    static SyncSendResult retry(int statusCode, String detail, String responseBody)
    {
        return new SyncSendResult(Outcome.RETRY, statusCode, detail == null ? "" : detail, responseBody == null ? "" : responseBody);
    }

    static SyncSendResult drop(int statusCode, String detail, String responseBody)
    {
        return new SyncSendResult(Outcome.DROP, statusCode, detail == null ? "" : detail, responseBody == null ? "" : responseBody);
    }

    enum Outcome
    {
        SUCCESS,
        RETRY,
        DROP
    }
}
