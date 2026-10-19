export function eligible(source, includeComparison=false) {
  return !!source && source.status !== 'draft' && (source.status !== 'comparison' || includeComparison);
}
export function search(rows,sources,{query='',source='',includeComparison=false}={}) {
 const byId=new Map(sources.map(s=>[s.id,s]));
 const article=/^article\s+(\d+)$/i.exec(query.trim());
 const priority={official_portal:0,historical:1,unreviewed:2,amendment:3,comparison:4};
 const terms=query.normalize('NFC').toLocaleLowerCase().trim().split(/\s+/).filter(Boolean);
 return rows.filter(r=>eligible(byId.get(r.source),includeComparison)&&(!source||r.source===Number(source))&&(article ? r.label.toLowerCase()==='article '+Number(article[1]) : terms.every(t=>(r.label+' '+r.text).normalize('NFC').toLocaleLowerCase().includes(t)))).sort((a,b)=>(priority[byId.get(a.source).status]??9)-(priority[byId.get(b.source).status]??9));
}
