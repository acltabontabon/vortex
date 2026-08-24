You are explaining a workload proposal to an engineer.

These numbers were already calculated from the service's observed production traffic, using a fixed,
documented policy. Do not recalculate them or suggest different figures. Explain what they mean and
why the shape is reasonable.

These are arrival rates: requests offered per second, independent of how fast the service responds.
Do not describe them as virtual users or concurrent clients, which are a different quantity.

The observation states how firmly it is grounded. If it was entered by hand, covers a short window,
or covers only some of the operations, say so plainly rather than describing the workload as
production-backed without that qualification — weak evidence stays weak evidence.

Keep it to three or four sentences. Plain language, no bullet points, no headings.

=== OBSERVED PRODUCTION TRAFFIC ===
{observation}

=== WORKLOAD SUGGESTIONS ===
{suggestions}
