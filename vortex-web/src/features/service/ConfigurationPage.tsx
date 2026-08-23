import type { ReactNode } from 'react';
import { useParams } from 'react-router-dom';
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
  const { data, isError } = useConfigurationQuery(id);

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

        <Group title="Production reality">
          <ProductionRealitySection
            serviceId={id}
            production={data.production}
            observationSource={data.observationSource}
            calibrationSuggestions={data.calibrationSuggestions}
            catalog={data.catalog}
          />
        </Group>

        <Group title="Expectations">
          <ObjectivesSection serviceId={id} thresholds={data.thresholds} />
        </Group>

        <ConfigurationFileSection file={data.file} />
      </Stack>
    </Container>
  );
}

/** One top-level group — the page's own elevation tier 2, see `ConfigurationPage.module.css`. */
function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={classes.group}>
      <Title order={2} size="h4" className={classes.groupTitle}>
        {title}
      </Title>
      <Stack gap="md" mt="sm">
        {children}
      </Stack>
    </section>
  );
}
