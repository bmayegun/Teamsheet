ALTER TABLE club_members
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    ADD COLUMN display_name TEXT NOT NULL DEFAULT 'Organiser';
