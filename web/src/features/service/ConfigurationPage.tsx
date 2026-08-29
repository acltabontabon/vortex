import type { ReactNode } from 'react';
import { useEffect } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { Container, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useConfigurationQuery } from '../../api/configuration';
import { errorFallback } from '../../lib/queryFallback';
import { ConfigurationCompleteness } from './configuration/ConfigurationCompleteness';
import { EnvironmentsSection } from './configuration/EnvironmentsSection';
import { LabSection } from './configuration/LabSection';
import { ReleaseSection } from './configuration/ReleaseSection';
import { ProductionRealitySection } from './configuration/ProductionRealitySection';
import { ObjectivesSection } from './configuration/ObjectivesSection';
import { DatasetsSection } from './configuration/DatasetsSection';
import { OperationsSection } from './configuration/OperationsSection';
import { ConfigurationFileSection } from './configuration/ConfigurationFileSection';
import classes from './ConfigurationPage.module.css';

/**
 * What Vortex currently knows about this service, as a small number of scannable groups rather
 * than one long sequence of forms: what the service is (operations, datasets, release), where
 * tests run (environments, local dependencies), what production reality is known (traffic and its
 * source), and what success means (objectives). Reading is the common case; each group shows its
 * current state and reveals editing machinery only once asked for it.
 */
export function ConfigurationPage() {
  const { id = '' } = useParams();
  const { hash } = useLocation();
  const { data, isError } = useConfigurationQuery(id);

  // Readiness "Fix" links have pointed at `configuration#production` and `#objectives` since they
  // were written, but the browser only honours a hash on a full navigation — and even then not
  // against a page still showing its loading skeleton. Hence the `data` dependency: it is what
  // makes this fire once the sections it is scrolling to actually exist.
  //
  // Instant, not smooth: arriving by anchor is a page entry, not a nudge within a list the way
  // OverviewPage's row scroll is — you asked for this section, so you should already be at it
  // rather than watching a page and a half go by. (A smooth scroll over this distance is also
  // simply dropped here, which is how the difference got noticed.)
  useEffect(() => {
    if (!data || !hash) return;
    const target = document.getElementById(hash.slice(1));
    // jsdom has no scrollIntoView at all; every real browser does.
    if (target && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ behavior: 'instant', block: 'start' });
    }
  }, [data, hash]);

  const error = errorFallback(isError, 'Could not load configuration',
      `/api/services/${id}/configuration did not respond. Reload the page to try again.`);
  if (error) return error;

  if (!data) return <Skeleton height={480} radius="md" />;

  return (
    <Container size={960} px={0}>
      <Stack gap="lg">
        <ConfigurationCompleteness configuration={data} />

        <Group title="Service definition">
          <div id="operations">
            <OperationsSection serviceId={id} catalog={data.catalog} />
          </div>
          <div id="datasets">
            <DatasetsSection serviceId={id} />
          </div>
          <ReleaseSection serviceId={id} serviceVersion={data.serviceVersion} />
        </Group>

        <Group title="Test environments">
          <div id="environments">
            <EnvironmentsSection
              serviceId={id}
              environments={data.environments}
              environmentTypes={data.environmentTypes}
              dependencyModes={data.dependencyModes}
            />
          </div>
          <div>
            <Text size="xs" c="dimmed" mb={4}>
              Service-wide — the same stack regardless of which environment above is selected.
            </Text>
            <LabSection serviceId={id} localLab={data.localLab} />
          </div>
        </Group>

        <Group title="Production reality" id="production">
          <ProductionRealitySection
            serviceId={id}
            serviceName={data.name}
            production={data.production}
            observationSource={data.observationSource}
            calibrationSuggestions={data.calibrationSuggestions}
            catalog={data.catalog}
          />
        </Group>

        <Group title="Expectations" id="objectives">
          <ObjectivesSection serviceId={id} />
        </Group>

        <ConfigurationFileSection file={data.file} />
      </Stack>
    </Container>
  );
}

/** One top-level group — the page's own elevation tier 2, see `ConfigurationPage.module.css`. */
function Group({ title, id, children }: { title: string; id?: string; children: ReactNode }) {
  return (
    <section className={classes.group} id={id}>
      <Title order={2} size="h4" className={classes.groupTitle}>
        {title}
      </Title>
      <Stack gap="md" mt="sm">
        {children}
      </Stack>
    </section>
  );
}
