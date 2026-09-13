"""Extract data/labels from supplied firmware evidence, not executable vendor code."""
from pathlib import Path
import re, json, xml.etree.ElementTree as ET
base=Path(__file__).resolve().parents[2]
resources=Path('/tmp/honda-inspect/DaSettings-resource-values.txt').read_text()
# Preserve resource configurations: the default strings in this APK are English.
configs={}; config=''; current=None
for line in resources.splitlines():
 m=re.match(r'      config (.*):',line)
 if m: config=m[1];continue
 m=re.match(r'        resource (0x[0-9a-f]+).*',line)
 if m:
  current=int(m[1],16);configs.setdefault(config,{})[current]=[];continue
 if current is not None:
  m=re.search(r'\(string(?:8|16)\) "(.*)"',line)
  if m: configs[config][current]=m[1].replace('\\n','\n').replace('\\"','"')
  m=re.search(r'#\d+ .*\(reference\) (0x[0-9a-f]+)',line)
  if m and isinstance(configs[config][current],list):configs[config][current].append(int(m[1],16))
def resource(rid,lang='(default)'):
 v=configs.get(lang,{}).get(rid,configs.get('(default)',{}).get(rid))
 if isinstance(v,list):return [resource(x,lang) for x in v]
 return v
smali=Path('/tmp/honda-inspect/deodex/DaSettings/com/mitsubishielectric/ada/app/dasettings/VehicleSettingDataControl.smali').read_text()
methods={'DriverAssistSystemVehicleSettingData':2,'MeterVehicleSettingData':3,'DrivingPositionVehicleSettingData':4,'KeylessAccessVehicleSettingData':5,'LightingVehicleSettingData':6,'DoorWindowVehicleSettingData':7,'WiperVehicleSettingData':8,'PowerTailgateSetupData':10,'IDSSetupData':11}
labels={}
for name,category in methods.items():
 method=re.search(r'\.method private static set'+name+r'\(.*?\.end method',smali,re.S)[0]
 switch=re.search(r'\.packed-switch (0x[0-9a-f]+)\n(.*?)\.end packed-switch',method,re.S)
 if not switch:continue
 for setting,blockname in enumerate(re.findall(r':(pswitch_\w+)',switch[2]),int(switch[1],16)):
  block=re.search(r'\n    :'+blockname+r'\n(.*?)(?=\n    :|\.end method)',method,re.S)
  if not block or '->setData(' not in block[1] or re.search(r'\bif-|\bpacked-switch|\bsparse-switch',block[1]):continue
  ids=re.findall(r'const v\d+, (0x7f\w+)',block[1]);title=next((int(x,16) for x in ids if x.startswith('0x7f08')),None);array=next((int(x,16) for x in ids if x.startswith('0x7f05')),None)
  if title:
   labels[(category,setting)]={lang:{'title':resource(title,lang),'options':resource(array,lang) if array else []} for lang in ['(default)','pt']}
raw=(base/'honda-cluster-analysis/vehicle_customize_config.xml').read_text(); rows=[]
for m in re.finditer(r'<Item ID="[^"]+">.*?</Item>',raw,re.S):
 x=ET.fromstring(m[0]);c=int(x.findtext('Category'),0);i=int(x.findtext('Id'),0)
 comment=re.search(r'<!--(.*?)-->',m[0],re.S)
 lab=labels.get((c,i),{})
 if (c,i)==(3,0x5c):lab={'(default)':{'title':'Tachometer','options':['On','Off']},'pt':{'title':'Conta-giros','options':['Ligado','Desligado']}}
 # Separate ExternalDisplay CustomizeChangePreset implements this row, outside
 # generic VehicleSettingDataControl. Its three API values are independently traced.
 if (c,i)==(3,0x5b):lab={'(default)':{'title':'Panel configuration','options':['Preset 1','Preset 2','Preset 3']},'pt':{'title':'Configuração do painel','options':['Configuração 1','Configuração 2','Configuração 3']}}
 if (c,i)==(1,1):lab={'(default)':{'title':'TPMS calibration','options':[]},'pt':{'title':'Calibração TPMS','options':[]}}
 rows.append({'category':c,'id':i,'description':comment[1] if comment else '', 'type':int(x.findtext('DataType'),0),'values':{str(int(e.get('key'),0)):int(e.text,0) for e in x.findall('DataList/entry')},'labels':lab,'hasRoute':bool(x.findall('InfoFrom/entry'))})
out=base/'honda-customizer/app/src/main/assets/catalog.json';out.write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
assert len(rows)==313 and len({(r['category'],r['id']) for r in rows})==313
print('Catalog:',len(rows),'settings;',len(labels),'stock title mappings')
print(next(r for r in rows if r['category']==3 and r['id']==0x36))
