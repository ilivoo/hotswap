package com.ilivoo.hotswap;

class ClassReader {
    private final byte[] b;
    private final int[] a;
    private final String[] c;
    private final int d;
    private final int header;

    public ClassReader(byte[] var1) {
        this.b = var1;
        if (this.readShort(6) > 52) {
            throw new IllegalArgumentException();
        } else {
            this.a = new int[this.readUnsignedShort(8)];
            int var4 = this.a.length;
            this.c = new String[var4];
            int var5 = 0;
            int var6 = 10;

            for(int var7 = 1; var7 < var4; ++var7) {
                this.a[var7] = var6 + 1;
                int var8;
                switch(var1[var6]) {
                    case 1:
                        var8 = 3 + this.readUnsignedShort(var6 + 1);
                        if (var8 > var5) {
                            var5 = var8;
                        }
                        break;
                    case 2:
                    case 7:
                    case 8:
                    case 13:
                    case 14:
                    case 16:
                    case 17:
                    default:
                        var8 = 3;
                        break;
                    case 3:
                    case 4:
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 18:
                        var8 = 5;
                        break;
                    case 5:
                    case 6:
                        var8 = 9;
                        ++var7;
                        break;
                    case 15:
                        var8 = 4;
                }

                var6 += var8;
            }

            this.d = var5;
            this.header = var6;
        }
    }

    private int getAccess() {
        return this.readUnsignedShort(this.header);
    }

    public String getClassName() {
        return this.readClass(this.header + 2, new char[this.d]);
    }

    public String getSuperName() {
        return this.readClass(this.header + 4, new char[this.d]);
    }

    public String[] getInterfaces() {
        int var1 = this.header + 6;
        int var2 = this.readUnsignedShort(var1);
        String[] var3 = new String[var2];
        if (var2 > 0) {
            char[] var4 = new char[this.d];

            for(int var5 = 0; var5 < var2; ++var5) {
                var1 += 2;
                var3[var5] = this.readClass(var1, var4);
            }
        }

        return var3;
    }

    private int getItemCount() {
        return this.a.length;
    }

    private int getItem(int var1) {
        return this.a[var1];
    }

    private int getMaxStringLength() {
        return this.d;
    }

    private int readByte(int var1) {
        return this.b[var1] & 255;
    }

    private int readUnsignedShort(int var1) {
        byte[] var2 = this.b;
        return (var2[var1] & 255) << 8 | var2[var1 + 1] & 255;
    }

    private short readShort(int var1) {
        byte[] var2 = this.b;
        return (short)((var2[var1] & 255) << 8 | var2[var1 + 1] & 255);
    }

    private int readInt(int var1) {
        byte[] var2 = this.b;
        return (var2[var1] & 255) << 24 | (var2[var1 + 1] & 255) << 16 | (var2[var1 + 2] & 255) << 8 | var2[var1 + 3] & 255;
    }

    private long readLong(int var1) {
        long var2 = (long)this.readInt(var1);
        long var4 = (long)this.readInt(var1 + 4) & 4294967295L;
        return var2 << 32 | var4;
    }

    private String readUTF8(int var1, char[] var2) {
        int var3 = this.readUnsignedShort(var1);
        if (var1 != 0 && var3 != 0) {
            String var4 = this.c[var3];
            if (var4 != null) {
                return var4;
            } else {
                var1 = this.a[var3];
                return this.c[var3] = this.a(var1 + 2, this.readUnsignedShort(var1), var2);
            }
        } else {
            return null;
        }
    }

    private String a(int var1, int var2, char[] var3) {
        int var4 = var1 + var2;
        byte[] var5 = this.b;
        int var6 = 0;
        byte var8 = 0;
        char var9 = 0;

        while(true) {
            while(var1 < var4) {
                byte var7 = var5[var1++];
                switch(var8) {
                    case 0:
                        int var10 = var7 & 255;
                        if (var10 < 128) {
                            var3[var6++] = (char)var10;
                        } else {
                            if (var10 < 224 && var10 > 191) {
                                var9 = (char)(var10 & 31);
                                var8 = 1;
                                continue;
                            }

                            var9 = (char)(var10 & 15);
                            var8 = 2;
                        }
                        break;
                    case 1:
                        var3[var6++] = (char)(var9 << 6 | var7 & 63);
                        var8 = 0;
                        break;
                    case 2:
                        var9 = (char)(var9 << 6 | var7 & 63);
                        var8 = 1;
                }
            }

            return new String(var3, 0, var6);
        }
    }

    private String readClass(int var1, char[] var2) {
        return this.readUTF8(this.a[this.readUnsignedShort(var1)], var2);
    }
}

