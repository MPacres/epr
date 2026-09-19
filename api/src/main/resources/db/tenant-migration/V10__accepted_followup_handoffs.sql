-- carecoordination: extend the existing task pathway, never create duplicate follow-up tasks.
ALTER TABLE clinical_owned_task ADD COLUMN proposed_owner_id UUID;
ALTER TABLE clinical_owned_task ADD COLUMN accepted_transfer BOOLEAN NOT NULL DEFAULT FALSE;
