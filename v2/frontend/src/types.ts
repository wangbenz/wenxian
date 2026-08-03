export type ThemeMode = "system" | "paper" | "night";
export type FontMode = "compact" | "standard" | "large";
export type TabId = "adventure" | "character" | "chronicle" | "world";
export type RaceCode = "HUMAN" | "DEMON" | "SPIRIT";
export type DifficultyCode = "NORMAL" | "HARD";

export interface AuthResponse {
  authenticated: boolean;
  newUser: boolean;
  user: { userId: string; email: string } | null;
  passwordConfigured: boolean;
  nextStep: string;
}

export interface Bootstrap {
  engineVersion: string;
  contentVersion: string;
  maxActiveSaves: number;
  region: string;
  races: RaceCode[];
  difficulties: DifficultyCode[];
  starterTalents: Array<{
    id: string;
    name: string;
    abilityType: string;
    raceScope: string;
    description: string;
  }>;
  deterministicActions: string[];
}

export interface SaveAggregate {
  save: {
    id: string;
    userId: string;
    slotNo: number;
    status: string;
    difficulty: DifficultyCode;
    protagonistId: string;
    engineVersion: string;
    contentVersion: string;
    stateVersion: number;
    turnNumber: number;
    endReason: string | null;
    createdAt: string;
    updatedAt: string;
    endedAt: string | null;
  };
  protagonist: {
    id: string;
    saveId: string;
    name: string;
    genderExpression: string;
    race: RaceCode;
    rootType: string;
    rootDescription: string;
    lifeGoal: string;
    status: string;
  };
  characterState: {
    characterId: string;
    locationId: string;
    locationName: string;
    lifeStageCode: string;
    realmCode: string;
    realmDisplayName: string;
    realmPhase: string;
    publicIdentity: string;
    bodyCondition: string;
    currentGoal: string;
    stateVersion: number;
  };
  origin: {
    fateTitle: string;
    publicIdentity: string;
    openingSituation: string;
    unpaidDebt: string;
  };
  worldMinute: number;
  worldDate: {
    year: number;
    day: number;
    minuteOfDay: number;
    display: string;
  };
  wallet: Array<{ currencyCode: string; currencyName: string; balance: number }>;
  inventory: Array<{
    instanceId: string;
    definitionId: string;
    itemCode: string;
    name: string;
    quantity: number;
    status: string;
    condition: string;
    provenance: string;
  }>;
  abilities: Array<{
    characterAbilityId: string;
    abilityCode: string;
    name: string;
    type: string;
    sourceType: string;
    sourceReferenceId: string | null;
    masteryStage: string;
    stability: string;
    status: string;
    learnedWorldMinute: number;
  }>;
  cultivation: {
    pathCode: string | null;
    pathName: string | null;
    currentRealmCode: string;
    currentRealmPhase: string;
    targetRealmCode: string | null;
    projectStage: string;
    insightSummary: string | null;
    stateVersion: number;
  };
  worldEvents: Array<{
    id: string;
    title: string;
    scale: string;
    stage: string;
    locationId: string | null;
    locationName: string | null;
    publicSummary: string;
  }>;
  knownCharacters: Array<{
    id: string;
    name: string;
    race: RaceCode;
    status: string;
    publicIdentity: string;
    locationId: string;
    locationName: string;
    realmCode: string;
    realmDisplayName: string;
    currentGoal: string;
  }>;
  relationships: Array<{
    id: string;
    targetCharacterId: string;
    targetName: string;
    relationshipType: string;
    trustLevel: number;
    closenessLevel: number;
    fearLevel: number;
    interestLevel: number;
    debtSummary: string | null;
    conflictSummary: string | null;
    sharedSecretSummary: string | null;
    lastChangedWorldMinute: number;
  }>;
  ending: {
    endingType: "RETIREMENT" | "LEGACY";
    title: string;
    summary: string;
    finalRealmCode: string;
    finalRealmDisplayName: string;
    finalLocationId: string;
    finalTurnNumber: number;
    endedWorldMinute: number;
    createdAt: string;
  } | null;
}

export interface KnownLocation {
  id: string;
  code: string;
  name: string;
  type: string;
  parentId: string | null;
  known: boolean;
  accessible: boolean;
  localState: string;
  directTravelMinutes: number | null;
}

export interface TurnSummary {
  id: string;
  turnNumber: number;
  playerAction: string;
  outcomeCode: string;
  outcomeSummary: string;
  narrativeText: string;
  choices: Array<{ title: string; actionText: string }>;
  worldMinuteBefore: number;
  worldMinuteAfter: number;
  stateVersionAfter: number;
  changes: Array<{
    domainType: string;
    changeType: string;
    before: string | null;
    after: string | null;
    reason: string;
  }>;
  createdAt: string;
}

export interface TurnOperation {
  id: string;
  requestId: string;
  saveId: string;
  expectedStateVersion: number;
  status: "QUEUED" | "INTERPRETING" | "RESOLVING" | "WRITING" | "COMMITTING" | "SUCCEEDED" | "REJECTED" | "FAILED";
  stage: string;
  actionType: string;
  actionText: string;
  resultTurnId?: string;
  errorCode?: string;
  errorMessage?: string;
  errorSuggestions?: string[];
  problemReference?: string;
}

export interface CreateSavePayload {
  name: string;
  genderExpression: string;
  race: RaceCode;
  rootType: string;
  rootDescription: string;
  lifeGoal: string;
  difficulty: DifficultyCode;
  starterTalentDefinitionId?: string;
  originProposalId?: string;
  fateCandidateId?: string;
}

export interface FateCandidate {
  id?: string;
  title: string;
  publicIdentity: string;
  openingSituation: string;
  unpaidDebt: string;
}

export interface OriginProposal {
  proposalId: string;
  expiresAt: string;
  candidates: FateCandidate[];
}

export interface UiProblem {
  code: string;
  category: string;
  message: string;
  suggestions: string[];
  problemReference?: string;
}

export interface AiSettings {
  credentialSource: "PLATFORM" | "USER";
  providerCode: string;
  modelCode: string;
  models: Array<{
    modelCode: string;
    providerCode: string;
    displayName: string;
  }>;
  credentialConfigured: boolean;
  maskedSuffix?: string;
  credentialStatus?: string;
  lastVerifiedAt?: string;
  userCredentialEnabled: boolean;
  turnLimit: number;
  usedTurns: number;
  remainingTurns: number;
  exhaustedMessage: string;
  contactText: string;
}

export interface ReleaseNotice {
  id: string;
  version: string;
  title: string;
  content: string;
  publishedAt?: string;
  unread: boolean;
}

export interface LegacyLife {
  id: string;
  source: "V0_13_ACTIVE" | "V0_13_ARCHIVE";
  endReason: string;
  characterName: string;
  race: string;
  realm: string;
  location: string;
  turnNumber: number;
  worldYear: number;
  day: number;
  minuteOfDay: number;
  recordedAt: string;
}

export interface LegacyChapter {
  turnNumber: number;
  playerAction: string;
  narrative: string;
  createdAt: string;
}
