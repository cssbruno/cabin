# Selected decompiled methods from supplied original Honda firmware.
# Source: /tmp/honda-inspect/deodex/AvApServiceApiLib/com/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter.smali

.method public writeToParcel(Landroid/os/Parcel;I)V
    .registers 4
    .param p1, "out"    # Landroid/os/Parcel;
    .param p2, "flags"    # I

    .prologue
    .line 53
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mMode:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 54
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mType:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 55
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mBrightness:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 56
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mContrast:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 57
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mBlackLevel:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 58
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mTint:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 59
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mDensity:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 60
    iget v0, p0, Lcom/mitsubishielectric/ada/appservice/avapservice/data/DisplayParameter;->mIllStep:I

    invoke-virtual {p1, v0}, Landroid/os/Parcel;->writeInt(I)V

    .line 61
    return-void
.end method
