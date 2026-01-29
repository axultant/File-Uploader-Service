
CREATE TABLE files (
                       id UUID PRIMARY KEY,
                       idempotency_key VARCHAR(255) UNIQUE NOT NULL,
                       filename VARCHAR(255) NOT NULL,
                       content_type VARCHAR(100),
                       size BIGINT,
                       status VARCHAR(20) NOT NULL, -- PENDING, COMPLETED, FAILED
                       storage_path VARCHAR(512),
                       created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE outbox (
                        id BIGSERIAL PRIMARY KEY,
                        event_type VARCHAR(50) NOT NULL,
                        payload TEXT NOT NULL,
                        status VARCHAR(20) DEFAULT 'NEW',
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_files_idempotency ON files(idempotency_key);
CREATE INDEX idx_outbox_status ON outbox(status) WHERE status = 'NEW';