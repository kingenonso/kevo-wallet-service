CREATE TABLE webhook_events (
    id BIGSERIAL PRIMARY KEY,
    provider_name VARCHAR(50) NOT NULL,
    event_id VARCHAR(150) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (provider_name, event_id)
);
