// Generated from api/contracts/clinical-openapi.json. Run node scripts/generate-clinical-contracts.mjs.
export type AcceptFollowUp = {
  "expectedVersion"?: number
  "reason"?: string
}

export type AcceptWork = {
  "expectedVersion"?: number
  "reason"?: string
}

export type AddDating = {
  "assessmentId"?: string
  "expectedVersion"?: number
  "estimatedDate"?: string | null
  "sourceDate"?: string | null
  "sourcePrecision"?: string
  "method"?: string
  "source"?: string
  "reviewStatus"?: string
}

export type Amendment = {
  "expectedVersion"?: number
  "originalSignatureId"?: string
  "reason"?: string
}

export type Answer = {
  "fieldId"?: string
  "groupItemId"?: string | null
  "value"?: Value | null
  "missingState"?: "NOT_ASSESSED" | "UNKNOWN" | "DECLINED" | "NOT_APPLICABLE" | null
  "source"?: Source
  "review"?: "NOT_REVIEWED" | "REVIEWED"
  "hiddenResolution"?: "RETAIN_RELEVANT" | "SUPERSEDED" | null
  "resolutionReason"?: string | null
}

export type Approval = {
  "expectedVersion"?: number
  "checksum"?: string
  "basis"?: string
}

export type Availability = {
  "expectedVersion"?: number
  "retired"?: boolean
  "reason"?: string
}

export type CancelStart = {
  "encounterId"?: string
  "reason"?: string
}

export type Cancelled = {
  "encounterId"?: string
  "fence"?: number
}

export type Capability = {
  "kind"?: string
  "read"?: boolean
  "prepare"?: boolean
  "authorize"?: boolean
  "manageExisting"?: boolean
  "unavailableReason"?: string
}

export type Change = {
  "fieldId"?: string
  "groupItemId"?: string | null
  "answer"?: Answer | null
}

export type ChartOpen = {
  "duplicateReviewId"?: string
}

export type ClinicalScope = {
  "practiceId"?: string
  "siteId"?: string
  "practiceName"?: string
  "siteName"?: string
  "clinicianName"?: string
  "patientCapabilities"?: Array<string>
}

export type Create = {
  "encounterId"?: string
  "releaseId"?: string
  "episodeId"?: string | null
  "careMode"?: string
  "clinicalTime"?: string
}

export type CreateEpisode = {
  "episodeId"?: string
  "status"?: string
  "source"?: string
  "clinicalTime"?: string | null
}

export type DatingAssessment = {
  "assessmentId"?: string
  "estimatedDate"?: string | null
  "sourceDate"?: string | null
  "sourcePrecision"?: string
  "method"?: string
  "source"?: string
  "reviewStatus"?: string
  "createdBy"?: string
  "receivedAt"?: string
}

export type DirectoryItem = {
  "patient"?: Patient
  "birthAccuracy"?: string
  "sex"?: string
  "city"?: string
  "lastVisit"?: string
}

export type Draft = {
  "draftId"?: string
  "definition"?: TemplateDefinition
  "checksum"?: string
  "version"?: number
  "changeSummary"?: string
  "approvedChecksum"?: string | null
  "approvedBy"?: string | null
  "approvedAt"?: string | null
  "approvalBasis"?: string | null
}

export type DraftCommand = {
  "draftId"?: string
  "expectedVersion"?: number
  "definition"?: TemplateDefinition
  "changeSummary"?: string
}

export type DuplicateCandidate = {
  "patient"?: Patient
  "reason"?: string
}

export type DuplicateCheck = {
  "data"?: PatientData
  "excludePatientId"?: string
}

export type DuplicateReview = {
  "reviewId"?: string
  "candidates"?: Array<DuplicateCandidate>
  "expiresAt"?: string
}

export type Encounter = {
  "encounterId"?: string
  "patientId"?: string
  "siteId"?: string
  "responsibleClinicianId"?: string
  "patient"?: Patient
  "release"?: Release
  "episodeSnapshot"?: Snapshot | null
  "careMode"?: string
  "careState"?: string
  "noteState"?: string
  "clinicalTime"?: string
  "receivedAt"?: string
  "endedAt"?: string | null
  "version"?: number
  "responseRevision"?: number
  "unsignedNoteOwner"?: string
  "reservationFence"?: number
  "answers"?: Array<Answer>
  "savedAt"?: string | null
  "sessionId"?: string | null
  "queueEntryId"?: string | null
}

export type Entry = {
  "queueEntryId"?: string
  "patientId"?: string
  "patient"?: Patient
  "state"?: string
  "encounterId"?: string | null
  "version"?: number
  "receivedAt"?: string
}

export type Episode = {
  "episodeId"?: string
  "status"?: string
  "responsibleClinicianId"?: string
  "source"?: string
  "clinicalTime"?: string | null
  "selectedDatingId"?: string | null
  "version"?: number
  "createdBy"?: string
  "receivedAt"?: string
}

export type Field = {
  "id"?: string
  "key"?: string
  "concept"?: string
  "label"?: string
  "type"?: "TEXT" | "MULTILINE_TEXT" | "INTEGER" | "DECIMAL" | "QUANTITY" | "DATE" | "DATETIME" | "SINGLE_CHOICE" | "MULTIPLE_CHOICE" | "ASSESSMENT_STATE" | "REFERENCE" | "GROUP"
  "options"?: Array<string>
  "units"?: Array<string>
  "allowedMissingStates"?: Array<"NOT_ASSESSED" | "UNKNOWN" | "DECLINED" | "NOT_APPLICABLE">
  "maxLength"?: number
  "maxItems"?: number
  "precision"?: number
  "requiredAtSign"?: boolean
  "sensitivity"?: "CLINICAL" | "RESTRICTED_REPRODUCTIVE"
  "visibleWhen"?: Rule | null
  "requiredWhen"?: Rule | null
  "fields"?: Array<Field>
}

export type FollowUp = {
  "intentId"?: string
  "expectedVersion"?: number
  "dueDate"?: string
  "instruction"?: string
}

export type FollowUpReceipt = {
  "intentId"?: string
  "ownerId"?: string
  "deliveryState"?: string
}

export type GroupDocument = {
  "documentId"?: string
  "groupId"?: string
  "members"?: Array<GroupMember>
  "artifactId"?: string
  "artifactHash"?: string
}

export type GroupMember = {
  "sourceId"?: string
  "version"?: number
  "state"?: string
  "artifactId"?: string
  "artifactHash"?: string
}

export type GroupPrint = {
  "documentId"?: string
}

export type History = {
  "itemId"?: string
  "version"?: number
  "account"?: string
  "source"?: string
  "reviewStatus"?: string
  "reason"?: string
  "actorId"?: string
  "receivedAt"?: string
}

export type IdentifierChanges = {
  "pin"?: IdentifierOperation
  "principalPin"?: IdentifierOperation
  "otherIdValue"?: IdentifierOperation
}

export type IdentifierMetadata = {
  "pin"?: string
  "principalPin"?: string
  "otherIdValue"?: string
  "verification"?: string
}

export type IdentifierOperation = {
  "operation"?: string
  "value"?: string
}

export type IncomingReport = {
  "id"?: string
  "version"?: number
  "kind"?: string
  "source"?: string
  "externalReference"?: string
  "content"?: string
  "occurredAt"?: string
  "receivedAt"?: string
  "correctsId"?: string | null
  "patientId"?: string | null
  "requestId"?: string | null
  "state"?: string
}

export type InterventionWork = {
  "sourceId"?: string
  "kind"?: string
  "patientId"?: string
  "encounterId"?: string
  "siteId"?: string
  "ownerId"?: string
  "proposedOwnerId"?: string | null
  "version"?: number
  "sourceVersion"?: number
  "instruction"?: string
  "dueDate"?: string | null
  "state"?: string
}

export type Item = {
  "linkId"?: string
  "summaryRevision"?: number
  "summary"?: Summary
}

export type Manifest = {
  "manifestId"?: string
  "interventionSetVersion"?: number
  "items"?: Array<Item>
  "checksum"?: string
}

export type MatchReport = {
  "expectedVersion"?: number
  "patientId"?: string
  "requestId"?: string
  "expectedRequestVersion"?: number
  "reason"?: string
  "confirmPatientAndSource"?: boolean
}

export type MedicationCatalogEntry = {
  "code"?: string
  "version"?: string
  "ingredient"?: string
}

export type OwnerChoice = {
  "userId"?: string
  "displayName"?: string
}

export type Page = {
  "interventionSetVersion"?: number
  "items"?: Array<Item>
  "total"?: number
  "page"?: number
  "pageSize"?: number
}

export type Patient = {
  "patientId"?: string
  "recordNumber"?: string
  "displayName"?: string
  "birthDate"?: string | null
  "identityStatus"?: string
  "version"?: number
  "birthAccuracy"?: string
}

export type PatientData = {
  "firstName"?: string
  "middleName"?: string
  "lastName"?: string
  "suffix"?: string
  "birthAccuracy"?: string
  "sex"?: string
  "phone"?: string
  "email"?: string
  "language"?: string
  "communication"?: string
  "street"?: string
  "barangay"?: string
  "city"?: string
  "province"?: string
  "postalCode"?: string
  "philhealthRole"?: string
  "contributor"?: string
  "informationSource"?: string
  "principalName"?: string
  "principalRelationship"?: string
  "representativeName"?: string
  "representativeRelationship"?: string
  "representativePhone"?: string
  "representativeAuthority"?: string
  "emergencyName"?: string
  "emergencyRelationship"?: string
  "emergencyPhone"?: string
  "otherIdType"?: string
  "otherIdIssuer"?: string
  "birthDate"?: string
  "provisional"?: boolean
  "pinUnavailable"?: boolean
}

export type PatientDetail = {
  "patient"?: Patient
  "data"?: PatientData
  "identifiers"?: IdentifierMetadata
  "originalDisplayName"?: string
  "canEdit"?: boolean
}

export type PatientDirectory = {
  "items"?: Array<DirectoryItem>
  "total"?: number
  "page"?: number
  "pageSize"?: number
  "visitHistoryState"?: string
  "refreshedAt"?: string
}

export type PatientEdit = {
  "expectedVersion"?: number
  "data"?: PatientData
  "identifiers"?: IdentifierChanges
  "duplicateReviewId"?: string
  "correctionReason"?: string
}

export type PatientRegistration = {
  "patientId"?: string
  "data"?: PatientData
  "identifiers"?: IdentifierChanges
  "duplicateReviewId"?: string
  "syntheticOnly"?: boolean
}

export type Preparation = {
  "preparationId"?: string
  "responseRevision"?: number
  "encounterVersion"?: number
  "artifactId"?: string
  "artifactHash"?: string
  "releaseChecksum"?: string
  "interventionSetVersion"?: number
  "interventionManifestId"?: string
}

export type Prepare = {
  "preparationId"?: string
  "expectedVersion"?: number
}

export type PrescriptionAuthorize = {
  "expectedVersion"?: number
  "preparationId"?: string
  "reviewedArtifactHash"?: string
  "confirmSyntheticReview"?: boolean
  "lateActionReason"?: string
  "duplicateReason"?: string
}

export type PrescriptionCreate = {
  "id"?: string
  "encounterId"?: string
  "occurredAt"?: string
  "data"?: PrescriptionData
  "supersedesId"?: string
}

export type PrescriptionData = {
  "indication"?: string
  "instructions"?: string
  "reviewId"?: string | null
  "items"?: Array<PrescriptionItem>
}

export type PrescriptionEvidence = {
  "expectedVersion"?: number
  "outcome"?: string
  "evidence"?: string
  "occurredAt"?: string
}

export type PrescriptionItem = {
  "itemId"?: string
  "ingredient"?: string
  "brand"?: string
  "strength"?: string
  "formulation"?: string
  "dose"?: number
  "doseUnit"?: string
  "route"?: string
  "frequency"?: string
  "duration"?: string
  "quantity"?: number
  "quantityUnit"?: string
  "repeats"?: number
  "instructions"?: string
  "catalogCode"?: string
  "catalogVersion"?: string
}

export type PrescriptionPreparation = {
  "preparationId"?: string
  "sourceVersion"?: number
  "artifactId"?: string
  "artifactHash"?: string
}

export type PrescriptionPrepare = {
  "preparationId"?: string
  "expectedVersion"?: number
}

export type PrescriptionReason = {
  "expectedVersion"?: number
  "reason"?: string
}

export type PrescriptionRecord = {
  "id"?: string
  "patientId"?: string
  "encounterId"?: string
  "siteId"?: string
  "version"?: number
  "state"?: string
  "deliveryState"?: string
  "fulfillmentState"?: string
  "reviewState"?: string
  "ownerId"?: string
  "authorId"?: string
  "occurredAt"?: string
  "receivedAt"?: string
  "authorizedAt"?: string | null
  "artifactId"?: string | null
  "artifactHash"?: string | null
  "supersedesId"?: string | null
  "data"?: PrescriptionData
}

export type PrescriptionUpdate = {
  "expectedVersion"?: number
  "data"?: PrescriptionData
}

export type Publication = {
  "releaseId"?: string
  "expectedVersion"?: number
}

export type QueueAdd = {
  "queueEntryId"?: string
  "patientId"?: string
  "expectedSessionVersion"?: number
}

export type QueueStart = {
  "expectedSessionVersion"?: number
  "expectedQueueVersion"?: number
  "encounterId"?: string
  "releaseId"?: string
  "episodeId"?: string | null
  "careMode"?: string
  "clinicalTime"?: string
}

export type QueueTransition = {
  "expectedVersion"?: number
  "target"?: string
  "reason"?: string
}

export type ReceiveReport = {
  "id"?: string
  "kind"?: string
  "source"?: string
  "externalReference"?: string
  "content"?: string
  "occurredAt"?: string
  "correctsId"?: string
}

export type ReferralAuthorize = {
  "expectedVersion"?: number
  "preparationId"?: string
  "reviewedArtifactHash"?: string
  "confirmSyntheticReview"?: boolean
  "lateActionReason"?: string
  "duplicateReason"?: string
}

export type ReferralCreate = {
  "id"?: string
  "encounterId"?: string
  "occurredAt"?: string
  "data"?: ReferralData
  "supersedesId"?: string
}

export type ReferralData = {
  "destination"?: string
  "clinicalQuestion"?: string
  "clinicalSummary"?: string
  "priority"?: string
  "requestedDate"?: string
  "disclosureReason"?: string
}

export type ReferralEvidence = {
  "expectedVersion"?: number
  "outcome"?: string
  "evidence"?: string
  "occurredAt"?: string
}

export type ReferralPreparation = {
  "preparationId"?: string
  "sourceVersion"?: number
  "artifactId"?: string
  "artifactHash"?: string
}

export type ReferralPrepare = {
  "preparationId"?: string
  "expectedVersion"?: number
}

export type ReferralReason = {
  "expectedVersion"?: number
  "reason"?: string
}

export type ReferralRecord = {
  "id"?: string
  "patientId"?: string
  "encounterId"?: string
  "siteId"?: string
  "version"?: number
  "state"?: string
  "deliveryState"?: string
  "fulfillmentState"?: string
  "reviewState"?: string
  "ownerId"?: string
  "authorId"?: string
  "occurredAt"?: string
  "receivedAt"?: string
  "authorizedAt"?: string | null
  "artifactId"?: string | null
  "artifactHash"?: string | null
  "supersedesId"?: string | null
  "data"?: ReferralData
}

export type ReferralUpdate = {
  "expectedVersion"?: number
  "data"?: ReferralData
}

export type Release = {
  "releaseId"?: string
  "definition"?: TemplateDefinition
  "checksum"?: string
  "approvedBy"?: string
  "approvedAt"?: string
  "approvalBasis"?: string
  "publishedBy"?: string
  "publishedAt"?: string
  "retired"?: boolean
  "availabilityVersion"?: number
}

export type Resolve = {
  "expectedVersion"?: number
  "reason"?: string
}

export type ReviewMedicationSafety = {
  "reviewId"?: string
  "allergyState"?: string
  "allergies"?: Array<string>
  "currentMedications"?: Array<string>
  "source"?: string
  "interactionsUnavailableAcknowledged"?: boolean
}

export type Rule = {
  "op"?: "EQ" | "IN" | "PRESENT" | "ALL" | "ANY" | "NOT"
  "fieldId"?: string | null
  "values"?: Array<string>
  "rules"?: Array<Rule>
}

export type SafetyReview = {
  "reviewId"?: string
  "allergyState"?: string
  "allergies"?: Array<string>
  "currentMedications"?: Array<string>
  "source"?: string
  "reviewedBy"?: string
  "reviewedAt"?: string
  "interactionsUnavailableAcknowledged"?: boolean
}

export type Save = {
  "expectedVersion"?: number
  "clientTime"?: string
  "changes"?: Array<Change>
}

export type SaveHistory = {
  "itemId"?: string
  "expectedVersion"?: number
  "account"?: string
  "source"?: string
  "reviewStatus"?: string
  "reason"?: string
}

export type Section = {
  "id"?: string
  "key"?: string
  "label"?: string
  "fields"?: Array<Field>
}

export type SelectDating = {
  "assessmentId"?: string
  "expectedVersion"?: number
  "reason"?: string
}

export type ServiceCatalogEntry = {
  "code"?: string
  "version"?: string
  "kind"?: string
  "label"?: string
}

export type ServiceRequestAuthorize = {
  "expectedVersion"?: number
  "preparationId"?: string
  "reviewedArtifactHash"?: string
  "confirmSyntheticReview"?: boolean
  "lateActionReason"?: string
  "duplicateReason"?: string
}

export type ServiceRequestBatch = {
  "groupId"?: string
  "requests"?: Array<ServiceRequestCreate>
}

export type ServiceRequestBatchResult = {
  "groupId"?: string
  "requests"?: Array<ServiceRequestRecord>
}

export type ServiceRequestCreate = {
  "id"?: string
  "encounterId"?: string
  "occurredAt"?: string
  "data"?: ServiceRequestData
  "supersedesId"?: string
}

export type ServiceRequestData = {
  "kind"?: string
  "groupId"?: string | null
  "catalogCode"?: string
  "catalogVersion"?: string
  "serviceName"?: string
  "indication"?: string
  "priority"?: string
  "destination"?: string
  "specimen"?: string
  "bodySite"?: string
  "laterality"?: string
  "instructions"?: string
  "requestedDate"?: string | null
}

export type ServiceRequestEvidence = {
  "expectedVersion"?: number
  "outcome"?: string
  "evidence"?: string
  "occurredAt"?: string
}

export type ServiceRequestPreparation = {
  "preparationId"?: string
  "sourceVersion"?: number
  "artifactId"?: string
  "artifactHash"?: string
}

export type ServiceRequestPrepare = {
  "preparationId"?: string
  "expectedVersion"?: number
}

export type ServiceRequestReason = {
  "expectedVersion"?: number
  "reason"?: string
}

export type ServiceRequestRecord = {
  "id"?: string
  "patientId"?: string
  "encounterId"?: string
  "siteId"?: string
  "version"?: number
  "state"?: string
  "deliveryState"?: string
  "fulfillmentState"?: string
  "reviewState"?: string
  "ownerId"?: string
  "authorId"?: string
  "occurredAt"?: string
  "receivedAt"?: string
  "authorizedAt"?: string | null
  "artifactId"?: string | null
  "artifactHash"?: string | null
  "supersedesId"?: string | null
  "data"?: ServiceRequestData
}

export type ServiceRequestUpdate = {
  "expectedVersion"?: number
  "data"?: ServiceRequestData
}

export type Session = {
  "sessionId"?: string
  "sessionDate"?: string
  "label"?: string
  "state"?: string
  "version"?: number
  "selected"?: boolean
  "selectionVersion"?: number
}

export type SessionCreate = {
  "sessionId"?: string
  "sessionDate"?: string
  "label"?: string
}

export type SessionTransition = {
  "expectedVersion"?: number
  "target"?: string
  "reason"?: string
  "expectedSelectionVersion"?: number
}

export type Sign = {
  "preparationId"?: string
  "expectedVersion"?: number
  "reviewedArtifactHash"?: string
  "confirmSyntheticReview"?: boolean
}

export type Signature = {
  "signatureId"?: string
  "preparationId"?: string
  "responseRevision"?: number
  "artifactId"?: string
  "artifactHash"?: string
  "signerId"?: string
  "signedAt"?: string
  "assuranceMethod"?: string
  "amendmentOf"?: string | null
  "amendmentReason"?: string | null
  "interventionSnapshotCaptured"?: boolean
}

export type Snapshot = {
  "episode"?: Episode
  "selectedDating"?: DatingAssessment | null
}

export type Source = {
  "kind"?: string
  "recordId"?: string | null
  "version"?: number | null
  "clinicalTime"?: string | null
  "description"?: string
}

export type Summary = {
  "kind"?: "PRESCRIPTION" | "LAB_ORDER" | "IMAGING_ORDER" | "REFERRAL" | "FOLLOW_UP"
  "sourceId"?: string
  "sourceVersion"?: number
  "title"?: string
  "details"?: Array<string>
  "clinicalState"?: string
  "deliveryState"?: string
  "fulfillmentState"?: string
  "reviewState"?: string
  "ownerId"?: string
  "dueDate"?: string | null
  "occurredAt"?: string
  "artifactId"?: string | null
  "artifactHash"?: string | null
  "groupId"?: string | null
}

export type Task = {
  "sourceId"?: string
  "patientId"?: string
  "patientName"?: string
  "recordNumber"?: string
  "encounterId"?: string
  "ownerId"?: string
  "kind"?: string
  "instruction"?: string
  "dueDate"?: string | null
  "state"?: string
  "version"?: number
  "acknowledgedAt"?: string | null
  "currentOwner"?: boolean
}

export type TeamAccess = {
  "expectedVersion"?: number
  "enabled"?: boolean
  "roles"?: Array<"PRACTICE_ADMINISTRATOR" | "CLINICIAN" | "NURSE" | "SECRETARY">
  "siteGrants"?: Array<TeamSiteGrant>
  "reason"?: string
}

export type TeamCreate = {
  "email"?: string
  "displayName"?: string
  "confirmedUserId"?: string
  "roles"?: Array<"PRACTICE_ADMINISTRATOR" | "CLINICIAN" | "NURSE" | "SECRETARY">
  "siteGrants"?: Array<TeamSiteGrant>
  "reason"?: string
}

export type TeamEvent = {
  "id"?: string
  "actorName"?: string
  "action"?: string
  "reason"?: string
  "beforeAccess"?: string
  "afterAccess"?: string
  "occurredAt"?: string
}

export type TeamLookup = {
  "email"?: string
}

export type TeamMember = {
  "userId"?: string
  "displayName"?: string
  "username"?: string
  "accountEnabled"?: boolean
  "setupRequired"?: boolean
  "enabled"?: boolean
  "version"?: number
  "roles"?: Array<"PRACTICE_ADMINISTRATOR" | "CLINICIAN" | "NURSE" | "SECRETARY">
  "siteGrants"?: Array<TeamSiteGrant>
  "canReissue"?: boolean
  "clinicalRemovalBlocked"?: boolean
}

export type TeamPage = {
  "items"?: Array<TeamMember>
  "total"?: number
  "page"?: number
  "size"?: number
}

export type TeamPassword = {
  "expectedVersion"?: number
  "reason"?: string
}

export type TeamResolution = {
  "userId"?: string
  "displayName"?: string
  "username"?: string
  "existing"?: boolean
}

export type TeamResult = {
  "member"?: TeamMember
  "temporaryPassword"?: string
  "replayed"?: boolean
}

export type TeamScope = {
  "practiceId"?: string
  "practiceName"?: string
  "clinicalEnabled"?: boolean
  "schemaVersion"?: string
}

export type TeamSite = {
  "siteId"?: string
  "name"?: string
}

export type TeamSiteGrant = {
  "siteId"?: string
  "reproductiveAccess"?: boolean
}

export type TemplateDefinition = {
  "schemaVersion"?: string
  "ruleVersion"?: string
  "rendererVersion"?: string
  "extractionVersion"?: string
  "terminologyVersion"?: string
  "templateKey"?: string
  "title"?: string
  "specialty"?: string
  "encounterType"?: string
  "businessVersion"?: string
  "requiresPregnancyEpisode"?: boolean
  "syntheticOnly"?: boolean
  "sections"?: Array<Section>
}

export type TransferFollowUp = {
  "expectedVersion"?: number
  "ownerId"?: string
  "reason"?: string
}

export type TransferWork = {
  "expectedVersion"?: number
  "ownerId"?: string
  "reason"?: string
}

export type Transition = {
  "expectedVersion"?: number
  "target"?: string
  "reason"?: string
}

export type Value = {
  "text"?: string | null
  "number"?: number | null
  "unit"?: string | null
  "choices"?: Array<string>
  "referenceId"?: string | null
  "referenceVersion"?: number | null
  "items"?: Array<string>
}
