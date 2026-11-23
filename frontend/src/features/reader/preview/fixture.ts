export interface PreviewSense { pos: string; meaning: string }

export const previewEntry = {
  word: 'children',
  form: 'children',
  ipa: '/ˈtʃɪldrən/',
  senses: [
    { pos: 'noun', meaning: '孩子们；儿童（child 的复数）' },
    { pos: 'noun', meaning: '（某人的）子女' },
    { pos: 'noun', meaning: '孩子气的人；幼稚的人' },
  ] satisfies PreviewSense[],
  context: 'The children are running toward the old house while the sun rose slowly over the quiet valley.',
  translation: '孩子们正朝那座老房子跑去，太阳慢慢升过安静的山谷。',
};
