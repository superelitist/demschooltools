# --- !Ups

ALTER TABLE testify_record ADD COLUMN testimony text NOT NULL DEFAULT '';

ALTER TABLE person_at_meeting ADD COLUMN id serial;

ALTER TABLE "case" ALTER meeting_id SET NOT NULL;

# --- !Downs

ALTER TABLE testify_record DROP COLUMN testimony;
ALTER TABLE person_at_meeting DROP COLUMN id;

ALTER TABLE "case" ALTER meeting_id DROP NOT NULL;
