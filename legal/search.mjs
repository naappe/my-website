export function eligible(source, includeComparison=false) {
  return !!source && source.status !== 'draft' && (source.status !== 'comparison' || includeComparison);
}
export function search(rows,sources,{query='',source='',includeComparison=false}={}) {
 const byId=new Map(sources.map(s=>[s.id,s]));
 const terms=query.normalize('NFC').toLocaleLowerCase().trim().split(/\s+/).filter(Boolean);
 return rows.filter(r=>eligible(byId.get(r.source),includeComparison)&&(!source||r.source===Number(source))&&terms.every(t=>(r.label+' '+r.text).normalize('NFC').toLocaleLowerCase().includes(t)));
}
