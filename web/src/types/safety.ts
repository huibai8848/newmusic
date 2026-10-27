import type { Id } from '@/types/api';

/** 模块 18：青少年模式、申诉与 AI 内容标识。 */

export interface MinorStatus {
  userId: string;
  minor: boolean;
  ageUnknown: boolean;
  defaultProtected: boolean;
  mode: boolean;
  forced: boolean;
  passwordSet: boolean;
  dailyLimitMinutes: number;
  usedMinutes: number;
  remainingMinutes: number;
  disableStart: string;
  disableEnd: string;
  inDisableWindow: boolean;
  allowedNow: boolean;
  socialAllowed: boolean;
  marketingAllowed: boolean;
  locationAllowed: boolean;
  guardianUserId?: string | null;
}

export interface Ward {
  userId?: Id;
  nickname?: string;
  mode?: number;
  usedMinutes?: number;
  dailyLimitMinutes?: number;
  guardianUserId?: Id;
  [key: string]: unknown;
}

export interface Appeal {
  id: Id;
  userId: Id;
  targetType: 'CONTENT' | 'ACCOUNT';
  targetId: string;
  reason: string;
  evidenceImages?: string;
  status: number;
  result?: string;
  reply?: string;
  handledAt?: string;
  slaDeadline?: string;
  createTime?: string;
}

export interface AppealCreateReq {
  targetType: 'CONTENT' | 'ACCOUNT';
  targetId: string;
  reason: string;
  evidenceImages?: string[];
}

export interface ContentLabel {
  contentType: string;
  contentId: string;
  aiGenerated: boolean;
  label?: string | null;
  aiModel?: string | null;
  aiPromptVersion?: string | null;
  aiGeneratedAt?: string | null;
}
