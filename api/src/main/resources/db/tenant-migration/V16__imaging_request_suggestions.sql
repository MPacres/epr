-- Local demonstration suggestions use real exam names, not standardized billing or terminology codes.
-- Keep the old catalog row for historical drafts and artifacts, but hide it from new suggestions.
UPDATE service_request_catalog SET retired = TRUE WHERE code = 'SYN-IMG-A' AND version = 'synthetic-1';

INSERT INTO service_request_catalog(code, version, kind, label) VALUES
    ('LOCAL-IMG-US-ABD', 'local-demo-1', 'IMAGING_ORDER', 'Ultrasound abdomen complete'),
    ('LOCAL-IMG-US-PELVIS-TA', 'local-demo-1', 'IMAGING_ORDER', 'Ultrasound pelvis transabdominal'),
    ('LOCAL-IMG-US-PELVIS-TV', 'local-demo-1', 'IMAGING_ORDER', 'Ultrasound pelvis transvaginal'),
    ('LOCAL-IMG-XR-CHEST', 'local-demo-1', 'IMAGING_ORDER', 'Chest radiograph'),
    ('LOCAL-IMG-CT-HEAD-NC', 'local-demo-1', 'IMAGING_ORDER', 'CT head without IV contrast'),
    ('LOCAL-IMG-CT-AP-C', 'local-demo-1', 'IMAGING_ORDER', 'CT abdomen and pelvis with IV contrast'),
    ('LOCAL-IMG-MR-BRAIN-WWO', 'local-demo-1', 'IMAGING_ORDER', 'MRI brain without and with IV contrast'),
    ('LOCAL-IMG-MR-PELVIS-WWO', 'local-demo-1', 'IMAGING_ORDER', 'MRI pelvis without and with IV contrast');
