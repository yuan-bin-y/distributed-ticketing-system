CREATE INDEX idx_session_event_status_id ON t_event_session(event_id,status,id);
CREATE INDEX idx_tier_session_enabled_id ON t_ticket_tier(session_id,enabled,id);
