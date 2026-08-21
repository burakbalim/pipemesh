-- What an execution has spent (§39).
--
-- On the execution row rather than in a table of its own, because unlike a lease
-- this is state of the execution: the budget decision is the execution's own, it
-- must survive a restart, and "what did this run cost" is a question about the
-- execution. One JSONB column keeps the record from growing four more.

ALTER TABLE workflow_execution
    ADD COLUMN spend JSONB NOT NULL
    DEFAULT '{"modelCalls":0,"unpricedCalls":0,"tokens":0,"costMicros":0}'::jsonb;
