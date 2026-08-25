import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMediaQuery } from '@mantine/hooks';
import { Grid, Text, Title } from '@mantine/core';
import { useTestsQuery } from '../../api/workspace';
import { TestComposer } from './TestComposer';
import { WorkloadPreviewPanel, type ComposerPreviewSnapshot } from './WorkloadPreviewPanel';
import classes from './TestEditorPage.module.css';

/**
 * The full-page route into the same composer `OverviewPage` renders inline — reached from an old
 * bookmarked `/tests/new` or `/tests/:name/edit` link, or from a workload name in the run history
 * (`AllRunsPage`). A thin shell around {@link TestComposer}/{@link WorkloadPreviewPanel} rather than
 * a second form implementation: this page used to carry its own parallel copy of the Load section,
 * which meant every change to the composer's design had to be made twice — and, in practice, only
 * ever was made once, so this page quietly fell behind. `TestComposer` makes no layout assumptions
 * (see its own module CSS) beyond needing somewhere to put its Workload Preview when there's room
 * for one, which is exactly what the rail column already provides.
 */
export function TestEditorPage() {
  const { id = '', name } = useParams();
  const editing = name !== undefined;
  const navigate = useNavigate();
  const isSideBySide = useMediaQuery('(min-width: 900px)');
  const [preview, setPreview] = useState<ComposerPreviewSnapshot | null>(null);

  const testsQuery = useTestsQuery(id);

  return (
    <Grid columnGap={48} rowGap="xl" align="start">
      <Grid.Col span={{ base: 12, md: 9 }}>
        <Title order={1} size="h2" mb={4}>
          {editing ? name : 'Define a test'}
        </Title>
        <Text c="dimmed" size="sm" mb="lg" maw={640}>
          A test describes a traffic condition this service experiences: how much load, split
          across which operations, held for how long.
        </Text>

        <TestComposer
          serviceId={id}
          mode={editing ? 'edit' : 'create'}
          editingName={name}
          onClose={() => navigate(`/services/${id}`)}
          onPreviewChange={setPreview}
          showInlineChart={!isSideBySide}
          target={testsQuery.data?.header.target ?? null}
        />
      </Grid.Col>

      <Grid.Col span={{ base: 12, md: 3 }} className={classes.railCol}>
        <WorkloadPreviewPanel snapshot={preview} showChart={!!isSideBySide} />
      </Grid.Col>
    </Grid>
  );
}
