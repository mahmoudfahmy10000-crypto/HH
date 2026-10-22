from pathlib import Path
import json,subprocess,concurrent.futures,datetime,urllib.parse
modules=json.loads(Path('audit/runtime-dependencies.json').read_text())
test=Path('audit/test-dependencies.json')
if test.exists():
    combined=modules+json.loads(test.read_text())
    modules=list({(m['group'],m['name'],m['version']):m for m in combined}.values())
def scan(m):
    coordinate=f"{m['group']}:{m['name']}@{m['version']}"
    endpoint='advisories?ecosystem=maven&per_page=100&affects='+urllib.parse.quote(coordinate,safe='')
    p=subprocess.run(['gh','api',endpoint],capture_output=True,text=True)
    if p.returncode:return {'coordinate':coordinate,'error':p.stderr[:500]}
    findings=json.loads(p.stdout)
    return {'coordinate':coordinate,'advisories':[{k:v.get(k) for k in ['ghsa_id','cve_id','summary','severity','html_url','vulnerabilities']} for v in findings]}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(scan,modules))
report={'source':'GitHub Global Security Advisories API (reviewed advisory default)', 'checked_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Resolved debugRuntimeClasspath and debugUnitTestRuntimeClasspath module versions; not an exhaustive malware or vulnerability certification','results':results}
Path('audit/dependency-advisories.json').write_text(json.dumps(report,indent=2))
print('Modules:',len(results),'Advisories:',sum(len(x.get('advisories',[])) for x in results),'Query errors:',sum('error' in x for x in results))
for r in results:
 if r.get('advisories') or r.get('error'):print(json.dumps(r))
