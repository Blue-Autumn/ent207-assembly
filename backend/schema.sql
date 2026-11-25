-- Records a unique attempt before calling the provider. caller_hash is empty for new requests.
CREATE TABLE IF NOT EXISTS requests (
    request_id TEXT PRIMARY KEY,
    at_seconds INTEGER NOT NULL,
    caller_hash TEXT NOT NULL,
    task TEXT NOT NULL CHECK (task IN ('review', 'design', 'comments'))
);
CREATE INDEX IF NOT EXISTS request_time ON requests(at_seconds);
CREATE INDEX IF NOT EXISTS request_caller_time ON requests(caller_hash, at_seconds);
