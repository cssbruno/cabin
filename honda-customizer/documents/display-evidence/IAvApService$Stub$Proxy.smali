# Selected decompiled methods from supplied original Honda firmware.
# Source: /tmp/honda-inspect/deodex/AvApServiceApiLib/com/mitsubishielectric/ada/appservice/avapservice/IAvApService$Stub$Proxy.smali

.method public getDisplayParameter(I)Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    .registers 8
    .param p1, "type"    # I
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Landroid/os/RemoteException;
        }
    .end annotation

    .prologue
    .line 3508
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;

    move-result-object v0

    .line 3509
    .local v0, "_data":Landroid/os/Parcel;
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;

    move-result-object v1

    .line 3512
    .local v1, "_reply":Landroid/os/Parcel;
    :try_start_8
    const-string v3, "com.mitsubishielectric.ada.appservice.avapservice.IAvApService"

    invoke-virtual {v0, v3}, Landroid/os/Parcel;->writeInterfaceToken(Ljava/lang/String;)V

    .line 3513
    invoke-virtual {v0, p1}, Landroid/os/Parcel;->writeInt(I)V

    .line 3514
    iget-object v3, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/IAvApService$Stub$Proxy;->mRemote:Landroid/os/IBinder;

    const/16 v4, 0x2c

    const/4 v5, 0x0

    invoke-interface {v3, v4, v0, v1, v5}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z

    .line 3515
    invoke-virtual {v1}, Landroid/os/Parcel;->readException()V

    .line 3516
    invoke-virtual {v1}, Landroid/os/Parcel;->readInt()I

    move-result v3

    if-eqz v3, :cond_30

    .line 3517
    sget-object v3, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->CREATOR:Landroid/os/Parcelable$Creator;

    invoke-interface {v3, v1}, Landroid/os/Parcelable$Creator;->createFromParcel(Landroid/os/Parcel;)Ljava/lang/Object;

    move-result-object v2

    check-cast v2, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    :try_end_29
    .catchall {:try_start_8 .. :try_end_29} :catchall_32

    .line 3524
    .local v2, "_result":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    :goto_29
    invoke-virtual {v1}, Landroid/os/Parcel;->recycle()V

    .line 3525
    invoke-virtual {v0}, Landroid/os/Parcel;->recycle()V

    .line 3527
    return-object v2

    .line 3520
    .end local v2    # "_result":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    :cond_30
    const/4 v2, 0x0

    .restart local v2    # "_result":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    goto :goto_29

    .line 3524
    .end local v2    # "_result":Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    :catchall_32
    move-exception v3

    invoke-virtual {v1}, Landroid/os/Parcel;->recycle()V

    .line 3525
    invoke-virtual {v0}, Landroid/os/Parcel;->recycle()V

    throw v3
.end method

.method public setDisplayParameter(ILcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;)Z
    .registers 10
    .param p1, "type"    # I
    .param p2, "param"    # Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Landroid/os/RemoteException;
        }
    .end annotation

    .prologue
    const/4 v2, 0x1

    const/4 v3, 0x0

    .line 3538
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;

    move-result-object v0

    .line 3539
    .local v0, "_data":Landroid/os/Parcel;
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;

    move-result-object v1

    .line 3542
    .local v1, "_reply":Landroid/os/Parcel;
    :try_start_a
    const-string v4, "com.mitsubishielectric.ada.appservice.avapservice.IAvApService"

    invoke-virtual {v0, v4}, Landroid/os/Parcel;->writeInterfaceToken(Ljava/lang/String;)V

    .line 3543
    invoke-virtual {v0, p1}, Landroid/os/Parcel;->writeInt(I)V

    .line 3544
    if-eqz p2, :cond_34

    .line 3545
    const/4 v4, 0x1

    invoke-virtual {v0, v4}, Landroid/os/Parcel;->writeInt(I)V

    .line 3546
    const/4 v4, 0x0

    invoke-virtual {p2, v0, v4}, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->writeToParcel(Landroid/os/Parcel;I)V

    .line 3551
    :goto_1c
    iget-object v4, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/IAvApService$Stub$Proxy;->mRemote:Landroid/os/IBinder;

    const/16 v5, 0x2d

    const/4 v6, 0x0

    invoke-interface {v4, v5, v0, v1, v6}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z

    .line 3552
    invoke-virtual {v1}, Landroid/os/Parcel;->readException()V

    .line 3553
    invoke-virtual {v1}, Landroid/os/Parcel;->readInt()I
    :try_end_2a
    .catchall {:try_start_a .. :try_end_2a} :catchall_39

    move-result v4

    if-eqz v4, :cond_41

    .line 3556
    .local v2, "_result":Z
    :goto_2d
    invoke-virtual {v1}, Landroid/os/Parcel;->recycle()V

    .line 3557
    invoke-virtual {v0}, Landroid/os/Parcel;->recycle()V

    .line 3559
    return v2

    .line 3549
    .end local v2    # "_result":Z
    :cond_34
    const/4 v4, 0x0

    :try_start_35
    invoke-virtual {v0, v4}, Landroid/os/Parcel;->writeInt(I)V
    :try_end_38
    .catchall {:try_start_35 .. :try_end_38} :catchall_39

    goto :goto_1c

    .line 3556
    :catchall_39
    move-exception v3

    invoke-virtual {v1}, Landroid/os/Parcel;->recycle()V

    .line 3557
    invoke-virtual {v0}, Landroid/os/Parcel;->recycle()V

    throw v3

    :cond_41
    move v2, v3

    .line 3553
    goto :goto_2d
.end method
