import {
  IconChartBar,
  IconChartHistogram,
  IconChartLine,
  IconClockHour4,
  IconCode,
  IconPlayerPlay,
  IconServer,
} from '@tabler/icons-react';

/**
 * The face of each signal, shared between the journey tracker and the chapter it opens onto.
 *
 * <p>Keyed on the domain's stable key rather than its label, and drawn from the workbench's own icon
 * set rather than invented — each one says what kind of *thing* the signal is, rather than the
 * tracker and the chapter reading as a row of identical bullets. A key nobody has given a face to
 * falls back to the generic one instead of rendering a hole.
 */
export function SignalIcon({ signalKey, size = 18 }: { signalKey: string; size?: number }) {
  const stroke = 1.7;
  switch (signalKey) {
    case 'API_IMPORTED':
      return <IconCode size={size} stroke={stroke} />;
    case 'ENVIRONMENT':
      return <IconServer size={size} stroke={stroke} />;
    case 'WORKLOAD':
      return <IconChartHistogram size={size} stroke={stroke} />;
    case 'AVERAGE_LOAD_WORKLOAD':
      return <IconChartBar size={size} stroke={stroke} />;
    case 'OBJECTIVES':
      return <IconClockHour4 size={size} stroke={stroke} />;
    case 'PRODUCTION_TRAFFIC':
      return <IconChartLine size={size} stroke={stroke} />;
    default:
      return <IconPlayerPlay size={size} stroke={stroke} />;
  }
}
