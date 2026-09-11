"""Exhaustively check every emitted integer temperature in the supported display domain."""
import json, pathlib, sys, multiprocessing, re
import javalang
from javalang import tree as T
from extract_syu_air import Facts,Unknown,Return
from syu_temperature_facts import FormatFacts

ROOT=pathlib.Path(sys.argv[1]);INPUT=pathlib.Path(sys.argv[2]);OUTPUT=pathlib.Path(sys.argv[3])
SOURCES={p.name:p.read_text() for p in ROOT.glob('*.java')}
CONSTANTS={k:int(v) for k,v in re.findall(r'public static final int (\w+) = (\d+);',SOURCES['FinalCanbus.java'])}
DEFAULTS={k:int(v) for k,v in re.findall(r'public static int (\w+) = (-?\d+);',SOURCES['ConstAllAirDara.java'])}
METHODS={name:{m.name:m for _,m in javalang.parse.parse(source).filter(T.MethodDeclaration)} for name,source in SOURCES.items() if name.startswith('Air_')}

def verify(row):
    formats=row.get('temperatureFormats',{})
    if not formats:return row
    profile=row['id']; ms=METHODS[row['screen']+'.java']; f=Facts(profile,CONSTANTS,DEFAULTS)
    try:f.run(ms['initCallbackId'].body)
    except (Unknown,KeyError):row.pop('temperatureFormats',None);return row
    env=f.env;valid={}
    for field,units in formats.items():
        method,view=('mUpdateAirTempLeft','air_sp_temp_left') if field.endswith('LEFT') else ('mUpdateTempRight','air_sp_temp_right')
        for unit,model in units.items():
            good=True
            for raw in range(1024):
                if raw in row.get('limits',{}).values():continue
                ff=FormatFacts(profile,CONSTANTS,env,ms,raw,int(unit),field)
                try:
                    try:ff.run(ms[method].body)
                    except Return:pass
                    text=ff.output[view].replace('℃','').replace('℉','').replace('°C','').replace('°F','')
                    if abs(float(text)-(raw*model['scale']+model['offset']))>1e-6:good=False;break
                    labels=' '.join(v for k,v in ff.output.items() if 'unit' in k)+ff.output[view]
                    detected='C' if '℃' in labels or '°C' in labels else 'F' if '℉' in labels or '°F' in labels else None
                    if detected!=model['unit']:good=False;break
                except (Unknown,KeyError,ValueError,TypeError,ZeroDivisionError):good=False;break
            if good:valid.setdefault(field,{})[unit]=dict(model,minRaw=0,maxRaw=1023)
    row['temperatureFormats']=valid
    return row

if __name__=='__main__':
    data=json.loads(INPUT.read_text());rows=[]
    with multiprocessing.Pool(4) as pool:
        for i,row in enumerate(pool.imap(verify,data['profiles'],chunksize=4),1):
            rows.append(row)
            if i%100==0:print('checked',i,flush=True)
    data['profiles']=rows
    data['temperatureFormatsVerified']=True
    OUTPUT.write_text(json.dumps(data,separators=(',',':'))+'\n')
    print('verified formatting profiles',sum(bool(p.get('temperatureFormats'))for p in rows),flush=True)
