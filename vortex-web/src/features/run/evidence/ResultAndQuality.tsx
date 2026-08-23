import { Text, Title } from '@mantine/core';
import type { Validity, VerdictSection as VerdictSectionData } from '../../../api/run';
import { VerdictBadge } from '../../../components/VerdictBadge';
import { Unknown } from '../../../components/Unknown';
import classes from './ResultAndQuality.module.css';

const GRADE_TONE: Record<string, string> = {
  VALID: 'pass',
  DEGRADED: 'warn',
  INVALID: 'fail',
  NOT_ASSESSED: 'neutral',
};

/**
 * Two separate conclusions, side by side rather than folded into one paragraph: whether the
 * experiment met its objectives, and whether the evidence behind that answer is trustworthy. A run
 * can pass while its evidence is degraded, and the reverse — this section is why that distinction,
 * scattered through the old report as ad hoc warnings, gets one fixed place instead.
 */
export function ResultAndQuality({
  verdict,
  validity,
}: {
  verdict: VerdictSectionData;
  validity: Validity;
}) {
  const tone = GRADE_TONE[validity.grade] ?? 'neutral';

  return (
    <div className={classes.grid}>
      <div className={classes.block}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          Result
        </Text>
        <div className={classes.headline}>
          <VerdictBadge verdict={verdict.verdict as 'PASS' | 'FAIL' | 'NOT_EVALUATED'} label={verdict.verdictLabel} size="lg" />
        </div>
        <Text size="sm" mt={4}>
          {verdict.answer}
        </Text>
        {verdict.qualifications.length > 0 && (
          <ul className={classes.qualifications}>
            {verdict.qualifications.map((q) => (
              <li key={q}>{q}</li>
            ))}
          </ul>
        )}
      </div>

      <div className={classes.block}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          Evidence quality
        </Text>
        {validity.assessed ? (
          <>
            <Title order={3} size="h4" mt={4} className={classes[`tone_${tone}`]}>
              {validity.label}
            </Title>
            <Text size="sm" mt={4}>
              {validity.explanation}
            </Text>
            {validity.grade !== 'VALID' && validity.findings.length > 0 && (
              <ul className={classes.qualifications}>
                {validity.findings.map((finding) => (
                  <li key={finding.code}>{finding.statement}</li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <Unknown
            compact
            what="This run's validity was never assessed"
            reason="It was recorded before Vortex graded experiments. Nothing here is withheld on that account."
          />
        )}
      </div>
    </div>
  );
}
