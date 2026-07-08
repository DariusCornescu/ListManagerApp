from decimal import Decimal
from pydantic import BaseModel, EmailStr, ConfigDict, Field
from typing import Optional
from datetime import datetime

# ===== Distributor Schemas =====
class DistributorBase(BaseModel):
    distributor_name: str

class DistributorCreate(DistributorBase):
    pass

class DistributorDTO(DistributorBase):
    id: int
    created_at: datetime
    
    model_config = ConfigDict(from_attributes=True)

# ===== Product Schemas =====
class ProductBase(BaseModel):
    name: str
    distributor_id: int
    aliases: Optional[str] = None
    price: Optional[Decimal] = Field(default=None, ge=0)

class ProductCreate(ProductBase):
    pass

class ProductDTO(ProductBase):
    id: int
    created_at: datetime
    
    model_config = ConfigDict(from_attributes=True)

# ===== Session Schemas =====
class GlobalSessionBase(BaseModel):
    name: str

class GlobalSessionCreate(GlobalSessionBase):
    # Optional: when set, creates a team-owned session (caller must be a member).
    team_id: Optional[int] = None

class GlobalSessionDTO(GlobalSessionBase):
    id: int
    is_active: bool
    created_at: datetime
    completed_at: Optional[datetime] = None
    version: int
    owner_user_id: Optional[int] = None
    team_id: Optional[int] = None

    model_config = ConfigDict(from_attributes=True)

# ===== Session Item Schemas =====
class GlobalSessionItemBase(BaseModel):
    session_id: int
    product_id: int
    quantity: int = 1

class GlobalSessionItemCreate(BaseModel):
    product_id: int
    quantity: int = 1
    idempotency_key: Optional[str] = None

class GlobalSessionItemUpdate(BaseModel):
    quantity: int
    version: int
    idempotency_key: Optional[str] = None

class GlobalSessionItemDTO(GlobalSessionItemBase):
    id: int
    version: int
    created_at: datetime
    updated_at: datetime
    
    product_name: Optional[str] = None
    distributor_name: Optional[str] = None
    item_uuid: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)

# ===== Auth Schemas =====
class UserCreate(BaseModel):
    username: str
    email: EmailStr
    password: str

class UserLogin(BaseModel):
    username: str
    password: str

class UserUpdate(BaseModel):
    email: Optional[EmailStr] = None
    password: Optional[str] = None

class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"

class UserDTO(BaseModel):
    id: int
    username: str
    email: str
    role: str
    is_active: bool

    model_config = ConfigDict(from_attributes=True)

# ===== Catalog Import Schemas =====
class ImportRowErrorDTO(BaseModel):
    line: int
    reason: str


class ImportResultDTO(BaseModel):
    new: int
    updated: int
    unchanged: int
    committed: bool
    errors: list[ImportRowErrorDTO]


# ===== Crash Reporting Schemas =====
class CrashReportCreate(BaseModel):
    app_version: Optional[str] = Field(default=None, max_length=50)
    android_version: Optional[str] = Field(default=None, max_length=50)
    device: Optional[str] = Field(default=None, max_length=100)
    username: Optional[str] = Field(default=None, max_length=50)
    stacktrace: str = Field(min_length=1, max_length=20000)


class CrashReportDTO(CrashReportCreate):
    id: int
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


# ===== Presence Schemas =====
class PresenceUserDTO(BaseModel):
    user_id: int
    username: str


# ===== Admin Dashboard Schemas =====
class StoreDTO(BaseModel):
    """A 'store' on the admin dashboard = a Team (workspace) with headcount."""
    id: int
    name: str
    member_count: int


class ActivityDayDTO(BaseModel):
    date: str  # ISO YYYY-MM-DD
    lists_completed: int
    items_added: int


class AdminDashboardDTO(BaseModel):
    stores: list[StoreDTO]
    users_count: int
    products_count: int
    distributors_count: int
    lists_completed_count: int
    crashes_count: int
    devices_count: int
    activity: list[ActivityDayDTO]
