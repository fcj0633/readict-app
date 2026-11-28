import type { SenseGroup } from './sense-groups';

export function SenseGroups({ groups, empty = '暂无释义' }: { groups: SenseGroup[]; empty?: string }) {
  if (!groups.length) return <p className="dictionary-sense-empty">{empty}</p>;
  return <div className="dictionary-senses">
    {groups.map(group => <div className="dictionary-sense" key={group.senseId}>
      <span>{group.rawPos ?? group.pos}</span>
      <p>{group.zh}</p>
    </div>)}
  </div>;
}
