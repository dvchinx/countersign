CREATE TABLE accounts (
    id      VARCHAR(40)    PRIMARY KEY,
    owner   VARCHAR(80)    NOT NULL,
    balance DECIMAL(15, 2) NOT NULL
);

CREATE TABLE transactions (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_account VARCHAR(40)    NOT NULL,
    to_account   VARCHAR(80)    NOT NULL,
    amount       DECIMAL(15, 2) NOT NULL,
    created_at   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL
);
