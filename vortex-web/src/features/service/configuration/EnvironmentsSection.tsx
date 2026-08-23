import { useForm } from '@mantine/form';
import { Badge, Button, Checkbox, Group, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Environment, EnvironmentOption } from '../../../api/configuration';
import { useAddEnvironmentMutation } from '../../../api/configuration';
import { Fact, Facts } from '../../../components/Fact';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { SectionDisclosure } from './SectionDisclosure';

export function EnvironmentsSection({
  serviceId,
  environments,
  environmentTypes,
  dependencyModes,
}: {
  serviceId: string;
  environments: Environment[];
  environmentTypes: EnvironmentOption[];
  dependencyModes: EnvironmentOption[];
}) {
  const mutation = useAddEnvironmentMutation(serviceId);

  const form = useForm({
    initialValues: {
      name: 'local',
      baseUrl: '',
      type: 'LOCAL_ISOLATED',
      dependencies: 'MOCKED',
      headerNames: '',
      headerValues: '',
      productionLike: false,
    },
    validate: {
      name: (value) => (value.trim() ? null : 'A name is required'),
      baseUrl: (value) => (value.trim() ? null : 'A target URL is required'),
    },
  });

  function submit(values: typeof form.values) {
    mutation.mutate(
      {
        name: values.name,
        baseUrl: values.baseUrl,
        type: values.type,
        dependencies: values.dependencies,
        productionLike: values.productionLike,
        headerNames: values.headerNames || undefined,
        headerValues: values.headerValues || undefined,
      },
      {
        onSuccess: (response) => {
          notifications.show({ message: response.message, color: 'pass' });
          form.setValues({ ...form.values, baseUrl: '', headerNames: '', headerValues: '' });
        },
      }
    );
  }

  const serverError = extractErrorMessage(mutation, 'Something went wrong saving this environment.');

  return (
    <SectionDisclosure
      id="environments"
      title="Environments"
      openByDefault={environments.length === 0}
      state={environments.length === 0 ? 'none configured' : `${environments.length} configured`}
    >
      <Stack gap="lg">
        {environments.length > 0 && (
          <Stack gap="sm">
            {environments.map((env) => (
              <div key={env.name}>
                <Facts>
                  <Fact label={env.name}>{env.baseUrl}</Fact>
                  <Fact label="Type">{env.typeLabel}</Fact>
                  <Fact label="Dependencies">{env.dependencyModeLabel}</Fact>
                  <Fact label="Classification">
                    <Badge size="sm" variant="light">
                      {env.classificationLabel}
                    </Badge>
                  </Fact>
                  {env.hasSecretReferences && (
                    <Fact label="Headers">
                      {Object.entries(env.maskedHeaders)
                        .map(([k, v]) => `${k}: ${v}`)
                        .join('  ')}
                    </Fact>
                  )}
                </Facts>
              </div>
            ))}
          </Stack>
        )}

        {serverError && (
          <Text size="sm" c="fail">
            {serverError}
          </Text>
        )}

        <form onSubmit={form.onSubmit(submit)}>
          <Stack gap="sm">
            <Group grow>
              <TextInput label="Name" placeholder="local" {...form.getInputProps('name')} />
              <TextInput
                label="Target URL"
                placeholder="http://localhost:8080"
                {...form.getInputProps('baseUrl')}
              />
            </Group>
            <Group grow>
              <Select
                label="Type"
                data={environmentTypes.map((t) => ({ value: t.name, label: t.label }))}
                {...form.getInputProps('type')}
              />
              <Select
                label="Dependencies"
                data={dependencyModes.map((m) => ({ value: m.name, label: m.label }))}
                {...form.getInputProps('dependencies')}
              />
            </Group>
            <Group grow align="flex-start">
              <Textarea
                label="Header names"
                description="one per line"
                minRows={2}
                {...form.getInputProps('headerNames')}
              />
              <Textarea
                label="Header values"
                description="paired by line, ${NAME} for secrets"
                minRows={2}
                {...form.getInputProps('headerValues')}
              />
            </Group>
            <Checkbox
              label="Sized and configured like production"
              {...form.getInputProps('productionLike', { type: 'checkbox' })}
            />
            <Group>
              <Button type="submit" size="sm" loading={mutation.isPending}>
                Save environment
              </Button>
            </Group>
          </Stack>
        </form>
      </Stack>
    </SectionDisclosure>
  );
}
