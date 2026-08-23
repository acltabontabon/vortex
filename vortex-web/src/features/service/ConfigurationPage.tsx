import { useParams } from 'react-router-dom';
import { Skeleton, Stack, Title } from '@mantine/core';
import { useConfigurationQuery } from '../../api/configuration';
import { errorFallback } from '../../lib/queryFallback';
import { EnvironmentsSection } from './configuration/EnvironmentsSection';
import { LabSection } from './configuration/LabSection';
import { ReleaseSection } from './configuration/ReleaseSection';
import { ProductionSection } from './configuration/ProductionSection';
import { ObservationSection } from './configuration/ObservationSection';
import { ObjectivesSection } from './configuration/ObjectivesSection';
import { DatasetsSection } from './configuration/DatasetsSection';
import { OperationsSection } from './configuration/OperationsSection';
import { ConfigurationFileSection } from './configuration/ConfigurationFileSection';
import classes from './ConfigurationPage.module.css';

/**
 * What Vortex currently knows about this service, grouped by what it is rather than where it is
 * stored: the service itself (operations, release), the reality it runs in (environments, local
 * lab, production traffic, observation source), and what is expected of it (objectives).
 *
 * <p>Replaces Understand's eight Thymeleaf sections. Reading is the common case here; editing is
 * occasional, so each section shows its current state collapsed and opens itself only while that
 * state is incomplete — the same rule `understand.html` used.
 */
export function ConfigurationPage() {
  const { id = '' } = useParams();
  const { data, isError } = useConfigurationQuery(id);

  const error = errorFallback(isError, 'Could not load configuration',
      `/api/services/${id}/configuration did not respond. Reload the page to try again.`);
  if (error) return error;

  if (!data) return <Skeleton height={480} radius="md" />;

  return (
    <Stack gap="xl" className={classes.page}>
      <section>
        <Title order={2} size="h4" mb="sm">
          Service
        </Title>
        <Stack gap="md">
          <div id="operations">
            <OperationsSection serviceId={id} catalog={data.catalog} />
          </div>
          <div id="datasets">
            <DatasetsSection serviceId={id} />
          </div>
          <ReleaseSection serviceId={id} serviceVersion={data.serviceVersion} />
        </Stack>
      </section>

      <section>
        <Title order={2} size="h4" mb="sm">
          Reality
        </Title>
        <Stack gap="md">
          <EnvironmentsSection
            serviceId={id}
            environments={data.environments}
            environmentTypes={data.environmentTypes}
            dependencyModes={data.dependencyModes}
          />
          <LabSection serviceId={id} localLab={data.localLab} />
          <ProductionSection
            serviceId={id}
            production={data.production}
            calibrationSuggestions={data.calibrationSuggestions}
            catalog={data.catalog}
          />
          <ObservationSection serviceId={id} source={data.observationSource} />
        </Stack>
      </section>

      <section>
        <Title order={2} size="h4" mb="sm">
          Expectations
        </Title>
        <ObjectivesSection serviceId={id} thresholds={data.thresholds} />
      </section>

      <ConfigurationFileSection file={data.file} />
    </Stack>
  );
}
