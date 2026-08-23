import { Title } from '@mantine/core';
import type { Resources } from '../../../api/run';
import { Unknown } from '../../../components/Unknown';
import { ResourceList } from './ResourceList';

/**
 * The system under test's own CPU and memory — and only the system under test's. What the load
 * generator and its host were doing is supporting context for how much to trust this run, not part
 * of how the service itself behaved, so it lives in "Evidence & provenance" instead of sharing this
 * primary section — see {@link EvidenceProvenanceSection}.
 */
export function ResourcesSection({ resources }: { resources: Resources }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Resources
      </Title>

      {resources.service.length > 0 ? (
        <ResourceList signals={resources.service} />
      ) : (
        <Unknown
          compact
          what="No resource signal describing the service was classified"
          reason="Nothing here can say whether the service ran out of anything. Configure an observability provider to find out."
        />
      )}
    </section>
  );
}
