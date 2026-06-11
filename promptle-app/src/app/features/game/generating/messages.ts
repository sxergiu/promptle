export const GENERATING_MESSAGES: string[] = [
  'Promptle is having a creative crisis...',
  'Mixing 47 shades of "close enough"',
  'Teaching pixels to behave themselves',
  'Your prompt is in the oven',
  'Consulting the ancient algorithms',
  'Promptle saw your prompt and said "interesting choice"',
  'Currently arguing with the neural network',
  'Generating... or procrastinating. Hard to tell.',
  'Pixels are unionizing. Please wait.',
  'Promptle is pretending it understood your prompt',
  'Adding happy little pixels',
  'Calculating the meaning of your prompt... nah, just drawing',
  'Warning: Promptle art may contain traces of chaos',
  'The hamsters powering Promptle are running faster',
  'Loading imagination.exe...',
  'Plot twist: Promptle is just googling it',
  'Your masterpiece is buffering',
  'Sprinkling some artificial intelligence on it',
  'Promptle read your prompt three times. Still confused.',
  'Almost done. Probably. Maybe.',
];

export function shuffleMessages(): string[] {
  const shuffled = [...GENERATING_MESSAGES];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled;
}
