from pydantic import BaseModel, EmailStr, ConfigDict
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

class GlobalSessionItemUpdate(BaseModel):
    quantity: int
    version: int  

class GlobalSessionItemDTO(GlobalSessionItemBase):
    id: int
    version: int
    created_at: datetime
    updated_at: datetime
    
    product_name: Optional[str] = None
    distributor_name: Optional[str] = None
    
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
