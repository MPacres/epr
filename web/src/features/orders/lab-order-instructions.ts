const fastingNote = 'Fasting required.'

export function splitLabInstructions(instructions: string): { instructions: string; fasting: boolean } {
  if (instructions === fastingNote) return { instructions: '', fasting: true }
  if (instructions.startsWith(`${fastingNote}\n`)) return { instructions: instructions.slice(fastingNote.length + 1), fasting: true }
  return { instructions, fasting: false }
}

export function combineLabInstructions(fasting: boolean, instructions: string): string {
  return fasting ? instructions ? `${fastingNote}\n${instructions}` : fastingNote : instructions
}
