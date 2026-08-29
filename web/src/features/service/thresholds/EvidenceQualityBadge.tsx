import { Badge } from '@mantine/core';

const COLOR: Record<string, string> = { STRONG: 'pass', MODERATE: 'warn', LIMITED: 'neutral' };
const LABEL: Record<string, string> = {
  STRONG: 'Strong evidence',
  MODERATE: 'Moderate evidence',
  LIMITED: 'Limited evidence',
};

/** A plain word, never a fake percentage — matches the three-tier vocabulary the backend computes. */
export function EvidenceQualityBadge({ quality }: { quality: string }) {
  return (
    <Badge color={COLOR[quality] ?? 'neutral'} variant="light" size="sm">
      {LABEL[quality] ?? quality}
    </Badge>
  );
}
