CREATE TABLE practice_site (
    site_id UUID NOT NULL,
    practice_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    facility_name VARCHAR(160),
    contact_number VARCHAR(30),
    country_code CHAR(2) NOT NULL,
    region_psgc_code CHAR(10) NOT NULL,
    province_area_psgc_code CHAR(10) NOT NULL,
    locality_psgc_code CHAR(10) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT practice_site_pk PRIMARY KEY (site_id),
    CONSTRAINT practice_site_practice_fk
        FOREIGN KEY (practice_id) REFERENCES tenant_practice (practice_id),
    CONSTRAINT practice_site_identity_unique UNIQUE (practice_id, site_id),
    CONSTRAINT practice_site_country_check CHECK (country_code = 'PH'),
    CONSTRAINT practice_site_region_code_check CHECK (region_psgc_code ~ '^[0-9]{10}$'),
    CONSTRAINT practice_site_province_area_code_check CHECK (province_area_psgc_code ~ '^[0-9]{10}$'),
    CONSTRAINT practice_site_locality_code_check CHECK (locality_psgc_code ~ '^[0-9]{10}$'),
    CONSTRAINT practice_site_time_zone_check CHECK (time_zone = 'Asia/Manila')
);

COMMENT ON TABLE practice_site IS
    'Practice-owned service locations. A shared facility does not imply a shared tenant or chart.';
