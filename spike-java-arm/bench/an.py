import csv,statistics as s,collections
d=collections.defaultdict(list); st=collections.defaultdict(list); rd=collections.defaultdict(list)
for r in csv.DictReader(open('results.csv')):
    v=float(r['value'])
    if r['kind']=='startup_ms': st[r['jvm']].append(v)
    else: d[(r['workload'],r['jvm'])].append(v); rd[(r['workload'],r['jvm'],r['round'].split('-')[0])].append(v)
print("workload,medA,medB,B/A,rangeA%,rangeB%,roundMedA(1,2),roundMedB(1,2)")
for w in ['interpreter','objects','hashmap','floatmath','grid']:
    a,b=d[(w,'A')],d[(w,'B')]
    ma,mb=s.median(a),s.median(b)
    rg=lambda x:(max(x)-min(x))/s.median(x)*100
    ra=[round(s.median(rd[(w,'A',r)])/1e6,2) for r in '12']; rb=[round(s.median(rd[(w,'B',r)])/1e6,2) for r in '12']
    print(f"{w},{ma/1e6:.2f}M,{mb/1e6:.2f}M,{mb/ma:.2f},{rg(a):.1f},{rg(b):.1f},{ra},{rb}")
for j in 'AB': print(j,'startup ms',st[j],'median',s.median(st[j]))
