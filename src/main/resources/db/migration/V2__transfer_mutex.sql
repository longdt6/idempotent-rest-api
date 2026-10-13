-- A shared lock row keeps request-key creation portable through JPA.
CREATE TABLE transfer_mutex (id BIGINT PRIMARY KEY);
INSERT INTO transfer_mutex (id) VALUES (1);
