import { Text } from '@mantine/core';
import type { ConfigurationFile } from '../../../api/configuration';
import { SectionDisclosure } from './SectionDisclosure';
import classes from '../ConfigurationPage.module.css';

/** The committed configuration, read-only — no form here changes anything. */
export function ConfigurationFileSection({ file }: { file: ConfigurationFile }) {
  return (
    <SectionDisclosure title="vortex.yaml" state="read-only" openByDefault={false}>
      {file.path && (
        <Text size="xs" c="dimmed" mb="xs">
          Written to <code>{file.path}</code>
        </Text>
      )}
      <pre className={classes.configFile}>{file.yaml}</pre>
    </SectionDisclosure>
  );
}
