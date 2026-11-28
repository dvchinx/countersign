INSERT INTO accounts (id, owner, balance) VALUES ('checking', 'alice', 12500.00);
INSERT INTO accounts (id, owner, balance) VALUES ('savings', 'alice', 40000.00);

INSERT INTO transactions (from_account, to_account, amount) VALUES ('checking', 'City Power', 84.20);
INSERT INTO transactions (from_account, to_account, amount) VALUES ('checking', 'savings', 500.00);

-- Public test card numbers (Visa and Mastercard test ranges), never real cards.
INSERT INTO cards (account, kind, number, expires) VALUES ('checking', 'debit', '4111 1111 1111 1111', '09/29');
INSERT INTO cards (account, kind, number, expires) VALUES ('savings', 'credit', '5555-5555-5555-4444', '03/28');
