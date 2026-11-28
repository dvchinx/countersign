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

CREATE TABLE cards (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    account VARCHAR(40) NOT NULL,
    kind    VARCHAR(20) NOT NULL,
    number  VARCHAR(23) NOT NULL,
    expires VARCHAR(5)  NOT NULL
);

-- Stands in for an outside helpdesk: what the support_open_ticket tool writes here has left the bank.
CREATE TABLE support_tickets (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    description VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL
);
