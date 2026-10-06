package com.byy.ticket.resilience;

/** 明确的远端4xx拒绝：保留业务处理，不纳入下游健康故障。 */
public class RemoteRequestRejectedException extends RuntimeException {
    public RemoteRequestRejectedException(String message) { super(message); }
}
