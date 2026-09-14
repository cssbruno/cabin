# Selected decompiled methods from supplied original Honda firmware.
# Source: /tmp/honda-av-display-source/com/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl.smali

.method public getDisplayParameter()Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    .registers 4

    .prologue
    .line 854
    const-string v1, ""

    invoke-static {v1}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    .line 856
    iget-object v1, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    iget v2, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayMode:I

    aget-object v1, v1, v2

    iget v2, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mVideoSource:I

    aget-object v0, v1, v2

    .line 859
    .local v0, "param":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    const-string v1, ""

    invoke-static {v1}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    .line 860
    return-object v0
.end method

.method public getDisplayParameter(I)Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    .registers 5
    .param p1, "type"    # I

    .prologue
    .line 870
    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    const-string v2, "type : "

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v1}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    .line 872
    const/4 v0, 0x0

    .line 873
    .local v0, "param":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    const/16 v1, 0xc

    if-ge p1, v1, :cond_23

    .line 874
    iget-object v1, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    iget v2, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayMode:I

    aget-object v1, v1, v2

    aget-object v0, v1, p1

    .line 878
    :cond_23
    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    const-string v2, "param : "

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v1}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    .line 879
    return-object v0
.end method

.method public getDisplayParameter(II)Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    .registers 6
    .param p1, "mode"    # I
    .param p2, "type"    # I

    .prologue
    .line 890
    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    const-string v2, "mode : "

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    move-result-object v1

    const-string v2, ", type : "

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1, p2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v1}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    .line 892
    const/4 v0, 0x0

    .line 893
    .local v0, "param":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    const/4 v1, 0x4

    if-ge p1, v1, :cond_2e

    const/16 v1, 0xc

    if-ge p2, v1, :cond_2e

    .line 895
    iget-object v1, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v1, v1, p1

    aget-object v0, v1, p2

    .line 898
    :cond_2e
    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    const-string v2, "param : "

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v1}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    .line 899
    return-object v0
.end method

.method public setDisplayParameter(Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;)Z
    .registers 9
    .param p1, "param"    # Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    .prologue
    const/4 v4, 0x1

    const/4 v3, 0x0

    .line 910
    const-string v5, ""

    invoke-static {v5}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    .line 913
    if-nez p1, :cond_f

    .line 914
    const-string v4, "parameter is null."

    invoke-static {v4}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    .line 965
    :goto_e
    return v3

    .line 918
    :cond_f
    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getMode()I

    move-result v1

    .line 919
    .local v1, "mode":I
    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getType()I

    move-result v2

    .line 920
    .local v2, "src":I
    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getIllStep()I

    move-result v0

    .line 922
    .local v0, "illStep":I
    const/4 v5, 0x3

    if-ge v1, v5, :cond_22

    const/16 v5, 0xc

    if-lt v2, v5, :cond_28

    .line 924
    :cond_22
    const-string v4, "Invalid parameter."

    invoke-static {v4}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    goto :goto_e

    .line 928
    :cond_28
    if-nez v1, :cond_31

    .line 930
    const-string v3, "NOP : mode is OFF"

    invoke-static {v3}, Lcom/mitsubishielectric/ada/util/ExpLog;->enter(Ljava/lang/String;)V

    move v3, v4

    .line 932
    goto :goto_e

    .line 936
    :cond_31
    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {v5}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getBrightness()I

    move-result v5

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getBrightness()I

    move-result v6

    if-ne v5, v6, :cond_b4

    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {v5}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getContrast()I

    move-result v5

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getContrast()I

    move-result v6

    if-ne v5, v6, :cond_b4

    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {v5}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getBlackLevel()I

    move-result v5

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getBlackLevel()I

    move-result v6

    if-ne v5, v6, :cond_b4

    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {v5}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getTint()I

    move-result v5

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getTint()I

    move-result v6

    if-ne v5, v6, :cond_b4

    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {v5}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getDensity()I

    move-result v5

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getDensity()I

    move-result v6

    if-ne v5, v6, :cond_b4

    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {v5}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getIllStep()I

    move-result v5

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getIllStep()I

    move-result v6

    if-ne v5, v6, :cond_b4

    .line 943
    new-instance v3, Ljava/lang/StringBuilder;

    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V

    const-string v5, "Exit for same value mode: "

    invoke-virtual {v3, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v3

    invoke-virtual {v3, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    move-result-object v3

    const-string v5, " src: "

    invoke-virtual {v3, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v3

    invoke-virtual {v3, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    move-result-object v3

    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v3

    invoke-static {v3}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    move v3, v4

    .line 944
    goto/16 :goto_e

    .line 948
    :cond_b4
    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getBrightness()I

    move-result v6

    invoke-virtual {v5, v6}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->setBrightness(I)V

    .line 949
    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getContrast()I

    move-result v6

    invoke-virtual {v5, v6}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->setContrast(I)V

    .line 950
    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getBlackLevel()I

    move-result v6

    invoke-virtual {v5, v6}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->setBlackLevel(I)V

    .line 951
    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getTint()I

    move-result v6

    invoke-virtual {v5, v6}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->setTint(I)V

    .line 952
    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getDensity()I

    move-result v6

    invoke-virtual {v5, v6}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->setDensity(I)V

    .line 953
    iget-object v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayParameter:[[Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;

    aget-object v5, v5, v1

    aget-object v5, v5, v2

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getIllStep()I

    move-result v6

    invoke-virtual {v5, v6}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->setIllStep(I)V

    .line 956
    iget v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mDisplayMode:I

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getMode()I

    move-result v6

    if-ne v5, v6, :cond_124

    iget v5, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->mVideoSource:I

    invoke-virtual {p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->getType()I

    move-result v6

    if-ne v5, v6, :cond_124

    .line 957
    invoke-direct {p0, p1}, Lcom/mitsubishielectric/ada/appservice/avapservice/setting/IlluminationControl;->setDisplayParameterToDevice(Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;)Z

    move-result v5

    if-nez v5, :cond_124

    .line 958
    const-string v4, "setDisplayParameterToDevice Error"

    invoke-static {v4}, Lcom/mitsubishielectric/ada/util/ExpLog;->warning(Ljava/lang/String;)V

    .line 959
    const-string v4, "false"

    invoke-static {v4}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    goto/16 :goto_e

    .line 964
    :cond_124
    const-string v3, "true"

    invoke-static {v3}, Lcom/mitsubishielectric/ada/util/ExpLog;->exit(Ljava/lang/String;)V

    move v3, v4

    .line 965
    goto/16 :goto_e
.end method
