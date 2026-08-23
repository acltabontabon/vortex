import { Text } from '@mantine/core';
import type { ConfigurationFile } from '../../../api/configuration';
import classes from './ConfigurationFileSection.module.css';

/** The committed configuration, read-only — no form here changes anything. */
export function ConfigurationFileSection({ file }: { file: ConfigurationFile }) {
  return (
    <details className={classes.wrap}>
      <summary className={classes.summary}>vortex.yaml</summary>
      <div className={classes.body}>
        {file.path && (
          <Text size="xs" c="dimmed" mb="xs">
            Written to <code>{file.path}</code>
          </Text>
        )}
        <pre className={classes.pre}>{file.yaml}</pre>
      </div>
    </details>
  );
}
