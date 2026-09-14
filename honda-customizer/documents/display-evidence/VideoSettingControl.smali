# Selected decompiled methods from supplied original Honda firmware.
# Source: /tmp/honda-av-display-source/com/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl.smali

.method public setBackupData()Z
    .registers 11

    .prologue
    .line 272
    const-string v8, ""

    invoke-static {v8}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    .line 275
    const/4 v6, 0x0

    .line 277
    .local v6, "result":Z
    iget-object v8, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl;->mIlluminationControl:Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;

    if-eqz v8, :cond_31

    .line 278
    const/4 v2, 0x1

    .line 279
    .local v2, "i":I
    :goto_b
    const/4 v8, 0x3

    if-ge v2, v8, :cond_31

    .line 280
    const/4 v3, 0x0

    .line 281
    .local v3, "j":I
    :goto_f
    const/16 v8, 0xc

    if-ge v3, v8, :cond_2e

    .line 282
    invoke-virtual {p0, v2, v3}, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl;->getDisplayParameter(II)Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    move-result-object v5

    .line 283
    .local v5, "param":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    if-nez v5, :cond_25

    .line 284
    const-string v8, "param is null."

    invoke-static {v8}, Lcom/mitsubishielectric/ada/util/ExpLog;->warning(Ljava/lang/String;)V

    .line 285
    const-string v8, "result: false"

    invoke-static {v8}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    .line 286
    const/4 v8, 0x0

    .line 308
    .end local v3    # "j":I
    .end local v5    # "param":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    :goto_24
    return v8

    .line 288
    .restart local v3    # "j":I
    .restart local v5    # "param":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    :cond_25
    iget-object v8, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl;->mIlluminationControl:Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;

    invoke-static {v5}, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->setDisplayParameterToVehicleDb(Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;)Z

    move-result v6

    .line 281
    add-int/lit8 v3, v3, 0x1

    goto :goto_f

    .line 279
    .end local v5    # "param":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    :cond_2e
    add-int/lit8 v2, v2, 0x1

    goto :goto_b

    .line 294
    .end local v2    # "i":I
    .end local v3    # "j":I
    :cond_31
    new-instance v1, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/DataBaseSetting;

    sget-object v8, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/DataBaseSetting$IVEHICLEDBMANAGER;->WIDE_MODE:Lcom/mitsubishielectric/ada/appservice/avapservice/setting/DataBaseSetting$IVEHICLEDBMANAGER;

    invoke-direct {v1, v8}, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/DataBaseSetting;-><init>(Lcom/mitsubishielectric/ada/appservice/avapservice/setting/DataBaseSetting$IVEHICLEDBMANAGER;)V

    .line 296
    .local v1, "db":Lcom/mitsubishielectric/ada/appservice/avapservice/setting/DataBaseSetting;
    const/4 v2, 0x0

    .restart local v2    # "i":I
    :goto_39
    sget-object v8, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl;->sWideModeTypeTable:Landroid/util/SparseIntArray;

    invoke-virtual {v8}, Landroid/util/SparseIntArray;->size()I

    move-result v8

    if-ge v2, v8, :cond_58

    .line 297
    sget-object v8, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl;->sWideModeTypeTable:Landroid/util/SparseIntArray;

    invoke-virtual {v8, v2}, Landroid/util/SparseIntArray;->keyAt(I)I

    move-result v7

    .line 299
    .local v7, "type":I
    sget-object v8, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl;->sWideModeTypeTable:Landroid/util/SparseIntArray;

    invoke-virtual {v8, v2}, Landroid/util/SparseIntArray;->valueAt(I)I

    move-result v4

    .line 300
    .local v4, "key":I
    iget-object v8, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/VideoSettingControl;->mWideMode:[I

    aget v0, v8, v4

    .line 302
    .local v0, "control":I
    invoke-virtual {v1, v7, v0}, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/DataBaseSetting;->write(II)Z

    move-result v6

    .line 296
    add-int/lit8 v2, v2, 0x1

    goto :goto_39

    .line 306
    .end local v0    # "control":I
    .end local v4    # "key":I
    .end local v7    # "type":I
    :cond_58
    new-instance v8, Ljava/lang/StringBuilder;

    invoke-direct {v8}, Ljava/lang/StringBuilder;-><init>()V

    const-string v9, "result: "

    invoke-virtual {v8, v9}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v8

    invoke-virtual {v8, v6}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;

    move-result-object v8

    invoke-virtual {v8}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v8

    invoke-static {v8}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    move v8, v6

    .line 308
    goto :goto_24
.end method
