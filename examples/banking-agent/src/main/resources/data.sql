INSERT INTO accounts (id, owner, balance) VALUES ('checking', 'alice', 12500.00);
INSERT INTO accounts (id, owner, balance) VALUES ('savings', 'alice', 40000.00);

INSERT INTO transactions (from_account, to_account, amount) VALUES ('checking', 'City Power', 84.20);
INSERT INTO transactions (from_account, to_account, amount) VALUES ('checking', 'savings', 500.00);
